# systemd - primary, nohup - fallback. Трогаем ТОЛЬКО наш процесс (unit/pidfile),
# без fuser/pkill по порту.
current_runtime() {
    local r
    r=$(state_get runtime)
    if [ -n "$r" ]; then echo "$r"; return 0; fi
    if has_systemd; then echo systemd; else echo nohup; fi
}

_running_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then return 0; fi
    fi
    pgrep -f "^$PREFIX/server-linux-" >/dev/null 2>&1
}

rt_running() {
    case "$(current_runtime)" in
        systemd) systemctl is-active --quiet "$UNIT_NAME" ;;
        nohup)   _running_nohup ;;
        *)       return 1 ;;
    esac
}

rt_pid() {
    case "$(current_runtime)" in
        systemd)
            local p
            p=$(systemctl show -p MainPID --value "$UNIT_NAME" 2>/dev/null || echo 0)
            # if, не &&-цепочка: MainPID=0 в хвосте вернул бы 1 и убил set -e у вызывающего.
            if [ -n "$p" ] && [ "$p" != "0" ]; then echo "$p"; fi ;;
        nohup)
            [ -f "$PIDFILE" ] && cat "$PIDFILE" 2>/dev/null || true ;;
    esac
}

current_cmdline() {
    local p
    p=$(rt_pid)
    if [ -n "$p" ] && [ -r "/proc/$p/cmdline" ]; then
        tr '\0' ' ' < "/proc/$p/cmdline"
    fi
}

# Ждём поднятия процесса до ~5с (быстрый bind-fail/битый конфиг успевает упасть).
_wait_running() {
    local i=0
    while [ "$i" -lt 5 ]; do
        rt_running >/dev/null 2>&1 && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

_install_systemd_unit() {
    local need_reload=0 launcher_content unit_content
    launcher_content=$(cat <<LAUNCH_EOF
#!/bin/bash
# Авто-сгенерирован free-turn-control.sh - не редактировать вручную.
set -e
PREFIX="$PREFIX"
mips_is_le() {
    local hex
    hex=\$(printf '\1\0' | od -An -tx2 -N2 2>/dev/null | tr -d ' \n')
    [ "\$hex" = "0001" ]
}
m=\$(uname -m)
case "\$m" in
    x86_64|amd64) arch=server-linux-amd64 ;;
    aarch64|arm64) arch=server-linux-arm64 ;;
    armv7l|armv6l|armv5*|arm) arch=server-linux-arm ;;
    i386|i486|i586|i686) arch=server-linux-386 ;;
    riscv64) arch=server-linux-riscv64 ;;
    mips64|mips64le) if mips_is_le; then arch=server-linux-mips64le; else echo "unsupported mips64 BE" >&2; exit 1; fi ;;
    mips|mipsel|mipsle) if mips_is_le; then arch=server-linux-mipsle; else arch=server-linux-mips; fi ;;
    *) echo "unsupported arch: \$m" >&2; exit 1 ;;
esac
[ -f "\$PREFIX/run.args" ] || { echo "run.args missing" >&2; exit 1; }
a=()
while IFS= read -r line || [ -n "\$line" ]; do a+=("\$line"); done < "\$PREFIX/run.args"
exec "\$PREFIX/\$arch" "\${a[@]}"
LAUNCH_EOF
)
    if [ ! -f "$LAUNCHER" ] || [ "$(cat "$LAUNCHER" 2>/dev/null)" != "$launcher_content" ]; then
        printf '%s\n' "$launcher_content" > "$LAUNCHER"
        chmod 755 "$LAUNCHER"
    fi

    unit_content=$(cat <<UNIT_EOF
[Unit]
Description=free-turn-proxy server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=-$ENVFILE
ExecStart=$LAUNCHER
Restart=on-failure
RestartSec=2
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT_EOF
)
    if [ ! -f "$UNIT_PATH" ] || [ "$(cat "$UNIT_PATH" 2>/dev/null)" != "$unit_content" ]; then
        printf '%s\n' "$unit_content" > "$UNIT_PATH"
        chmod 644 "$UNIT_PATH"
        need_reload=1
    fi
    [ "$need_reload" = "1" ] && systemctl daemon-reload
    systemctl enable "$UNIT_NAME" >/dev/null 2>&1 || true
}

_write_args_file() {
    local tmp="$ARGSFILE.tmp"
    ( umask 077; : > "$tmp" )
    {
        echo "-listen";  echo "$ARG_LISTEN"
        echo "-connect"; echo "$ARG_CONNECT"
        if [ "$ARG_MODE" = "tcp" ]; then
            echo "-mode"; echo "tcp"
            # Токены уже готовы (пары флаг/значение и -kcp-acknodelay=BOOL) - по строке на токен.
            for tok in ${ARG_KCP[@]+"${ARG_KCP[@]}"}; do echo "$tok"; done
        fi
        if [ "$ARG_OBF_PROFILE" != "none" ] && [ -n "$ARG_OBF_KEY" ]; then
            echo "-obf-profile"; echo "$ARG_OBF_PROFILE"
            echo "-obf-key";     echo "$ARG_OBF_KEY"
            # Пейсинг без профиля ядро отвергает - только внутри этой ветки.
            if [ -n "$ARG_OBF_TIMING" ]; then echo "-obf-timing"; echo "$ARG_OBF_TIMING"; fi
        fi
        # Авторизация включается только когда владелец передал cid (cmd_start уже
        # посадил его в allowlist - иначе lockout самого себя).
        if [ -n "$ARG_CLIENT_ID" ]; then echo "-clients-file"; echo "$CLIENTSFILE"; fi
    } >> "$tmp"
    chmod 600 "$tmp"
    mv -f "$tmp" "$ARGSFILE"
}

# run.env сейчас пуст (KCP FEC выпилен), оставлен для совместимости unit-а.
_write_env_file() {
    local tmp="$ENVFILE.tmp"
    ( umask 077; : > "$tmp" )
    chmod 600 "$tmp"
    mv -f "$tmp" "$ENVFILE"
}

# Завершить остаточный nohup-процесс прошлой версии (маркер - PIDFILE; systemd
# его не пишет, поэтому живой systemd-процесс под pgrep не трогаем).
_kill_legacy_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            sleep 1
            kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    pkill -9 -f "^$PREFIX/server-linux-" 2>/dev/null || true
}

_rt_start_systemd() {
    local bin=$1
    _write_args_file
    _write_env_file
    systemctl restart "$UNIT_NAME" || fail start_failed "systemctl restart failed"
    if ! _wait_running; then
        if [ -f "$bin.bak" ]; then
            log "process not active; rolling back .bak"
            mv -f "$bin.bak" "$bin"
            systemctl restart "$UNIT_NAME" || true
            _wait_running || fail start_failed "server failed to start (after rollback); journalctl -u $UNIT_NAME"
        else
            fail start_failed "server failed to start; journalctl -u $UNIT_NAME"
        fi
    fi
    rm -f "$bin.bak"
}

_rt_start_nohup() {
    local bin=$1
    if _running_nohup; then _stop_nohup; fi
    _write_env_file
    if [ -f "$ENVFILE" ]; then set -a; . "$ENVFILE"; set +a; fi
    local args=(-listen "$ARG_LISTEN" -connect "$ARG_CONNECT")
    if [ "$ARG_MODE" = "tcp" ]; then
        args+=(-mode tcp)
        args+=(${ARG_KCP[@]+"${ARG_KCP[@]}"})
    fi
    [ -n "$ARG_CLIENT_ID" ] && args+=(-clients-file "$CLIENTSFILE")
    if [ "$ARG_OBF_PROFILE" != "none" ] && [ -n "$ARG_OBF_KEY" ]; then
        ( umask 077; printf '%s' "$ARG_OBF_KEY" > "$OBFFILE" ); chmod 600 "$OBFFILE"
        args+=(-obf-profile "$ARG_OBF_PROFILE" -obf-key "$ARG_OBF_KEY")
        [ -n "$ARG_OBF_TIMING" ] && args+=(-obf-timing "$ARG_OBF_TIMING")
    fi
    ( cd "$PREFIX" && nohup "$bin" "${args[@]}" >"$LOGFILE" 2>&1 & echo $! > "$PIDFILE" )
    local pid
    pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
    sleep 1
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        if [ -f "$bin.bak" ]; then log "process died; rolling back .bak"; mv -f "$bin.bak" "$bin"; fi
        rm -f "$PIDFILE"
        fail start_failed "server failed to start; see logs"
    fi
    rm -f "$bin.bak"
}

# rt_start BIN - запускает ядро текущим рантаймом. emit PID.
rt_start() {
    local bin=$1
    case "$(current_runtime)" in
        systemd) _install_systemd_unit; _rt_start_systemd "$bin" ;;
        nohup)   _rt_start_nohup "$bin" ;;
        *)       fail start_failed "unknown runtime" ;;
    esac
    local pid
    pid=$(rt_pid)
    if [ -n "$pid" ]; then d_num pid "$pid"; fi
}

_stop_nohup() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || echo "")
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            sleep 1
            kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    pkill -9 -f "^$PREFIX/server-linux-" 2>/dev/null || true
    rm -f "$OBFFILE" "$ENVFILE"
}

_stop_systemd() {
    # Не disable - автозапуск сохраняется между ручными stop/start.
    systemctl stop "$UNIT_NAME" 2>/dev/null || true
    rm -f "$ENVFILE"
}

rt_stop() {
    case "$(current_runtime)" in
        systemd) _stop_systemd ;;
        nohup)   _stop_nohup ;;
    esac
}

# Best-effort: открыть udp-порт в UFW, если активен. Прочие firewall не трогаем.
_open_firewall() {
    local port=${1:-}
    [[ "$port" =~ ^[0-9]+$ ]] || return 0
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "${port}/udp" >/dev/null 2>&1 || true
    fi
}

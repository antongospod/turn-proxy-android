ARG_LISTEN=""
ARG_CONNECT=""
ARG_MODE="udp"
# Готовые argv-токены -kcp-*; пусто = сервер берёт свои дефолты, как и клиент.
ARG_KCP=()
ARG_OBF_PROFILE="none"
ARG_OBF_KEY=""
ARG_OBF_TIMING=""
ARG_TAIL=80
ARG_WG_PORT=""
ARG_WG_ENDPOINT=""
ARG_NAME_B64=""
ARG_PUBKEY=""
ARG_CLIENT_ID=""
ARG_SHA256=""
ARG_DNS="1.1.1.1"
ARG_WITH_WG_PKG=0
ARG_DRY_RUN=0

# --kcp-foo=N -> argv-пара "-kcp-foo" "N"; диапазоны проверяет само ядро.
_kcp_num_arg() {
    local flag=${1%%=*} value=${1#*=}
    [[ "$value" =~ ^[0-9]+$ ]] || fail bad_arg "bad $flag (need number)"
    ARG_KCP+=("-${flag#--}" "$value")
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --listen=*)
                ARG_LISTEN="${1#*=}"
                [[ "$ARG_LISTEN" =~ ^[a-zA-Z0-9.:_-]+$ ]] || fail bad_arg "bad --listen" ;;
            --connect=*)
                ARG_CONNECT="${1#*=}"
                [[ "$ARG_CONNECT" =~ ^[a-zA-Z0-9.:_-]+$ ]] || fail bad_arg "bad --connect" ;;
            --mode=*)
                ARG_MODE="${1#*=}"
                [[ "$ARG_MODE" =~ ^(udp|tcp)$ ]] || fail bad_arg "bad --mode (udp|tcp)" ;;
            --kcp-nodelay=*|--kcp-interval=*|--kcp-resend=*|--kcp-nc=*|--kcp-sndwnd=*|--kcp-rcvwnd=*|--kcp-mtu=*)
                _kcp_num_arg "$1" ;;
            --kcp-acknodelay=*)
                [[ "${1#*=}" =~ ^(true|false)$ ]] || fail bad_arg "bad $1 (true|false)"
                ARG_KCP+=("-kcp-acknodelay=${1#*=}") ;;
            --obf-profile=*)
                ARG_OBF_PROFILE="${1#*=}"
                [[ "$ARG_OBF_PROFILE" =~ ^(none|rtpopus|rtpopus2|rtpopus3)$ ]] || fail bad_arg "bad --obf-profile" ;;
            --obf-timing=*)
                ARG_OBF_TIMING="${1#*=}"
                [[ "$ARG_OBF_TIMING" =~ ^[0-9]{1,5}ms$ ]] || fail bad_arg "bad --obf-timing (need <N>ms)" ;;
            --obf-key=*)
                ARG_OBF_KEY="${1#*=}"
                [[ "$ARG_OBF_KEY" =~ ^[0-9a-fA-F]{64}$ ]] || fail bad_arg "bad --obf-key (need 64 hex)" ;;
            --tail=*)
                ARG_TAIL="${1#*=}"
                [[ "$ARG_TAIL" =~ ^[0-9]+$ ]] || fail bad_arg "bad --tail" ;;
            --port=*)
                ARG_WG_PORT="${1#*=}"
                [[ "$ARG_WG_PORT" =~ ^[0-9]+$ ]] || fail bad_arg "bad --port" ;;
            --endpoint=*)
                ARG_WG_ENDPOINT="${1#*=}"
                # host:port или [IPv6]:port
                [[ "$ARG_WG_ENDPOINT" =~ ^(\[[0-9a-fA-F:]+\]|[a-zA-Z0-9._-]+):[0-9]{1,5}$ ]] \
                    || fail bad_arg "bad --endpoint (host:port | [v6]:port)" ;;
            --name-b64=*)
                # Имя пира всегда base64(UTF-8): юникод/кавычки не трогают shell.
                ARG_NAME_B64="${1#*=}"
                [[ "$ARG_NAME_B64" =~ ^[A-Za-z0-9+/=]{1,256}$ ]] || fail bad_arg "bad --name-b64" ;;
            --pubkey=*)
                ARG_PUBKEY="${1#*=}"
                [[ "$ARG_PUBKEY" =~ ^[A-Za-z0-9+/]{43}=$ ]] || fail bad_arg "bad --pubkey (need wg base64)" ;;
            --client-id=*)
                ARG_CLIENT_ID="${1#*=}"
                [[ "$ARG_CLIENT_ID" =~ ^[0-9a-fA-F]{32}$ ]] || fail bad_arg "bad --client-id (need 32 hex)" ;;
            --sha256=*)
                ARG_SHA256="${1#*=}"
                [[ "$ARG_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] || fail bad_arg "bad --sha256 (need 64 hex)" ;;
            --dns=*)
                ARG_DNS="${1#*=}"
                [[ "$ARG_DNS" =~ ^[0-9a-zA-Z.:,_-]+$ ]] || fail bad_arg "bad --dns" ;;
            --with-wg-pkg) ARG_WITH_WG_PKG=1 ;;
            --dry-run)     ARG_DRY_RUN=1 ;;
            *) fail bad_arg "unknown arg: $1" ;;
        esac
        shift
    done
}

# Только чтение; чужой WG лишь репортится в conflicts{}.
cmd_probe() {
    stage probe

    local arch bin="" installed=false running=false version="" sha="" obf="" mode=""
    arch=$(detect_arch) || arch=""
    [ -n "$arch" ] && bin="$PREFIX/$arch"
    if [ -n "$bin" ] && [ -x "$bin" ]; then
        installed=true
        sha=$(sha256sum "$bin" 2>/dev/null | awk '{print $1}' || true)
        if [ -f "$VERFILE" ]; then version=$(cat "$VERFILE" 2>/dev/null || true); fi
    fi

    local runtime
    runtime=$(current_runtime)
    if rt_running; then
        running=true
        local cmdline
        cmdline=$(current_cmdline)
        obf=$(printf '%s' "$cmdline" | sed -nE 's/.*-obf-profile[= ]+([a-z0-9]+).*/\1/p')
        [ -z "$obf" ] && obf=none
        # -mode пишется только для tcp: без флага сервер работает в udp.
        mode=$(printf '%s' "$cmdline" | sed -nE 's/.*-mode[= ]+(udp|tcp).*/\1/p')
        [ -z "$mode" ] && mode=udp
    fi

    local euid
    euid=$(id -u 2>/dev/null || echo -1)

    local wgpresent=false wgp
    if wg_present; then wgpresent=true; fi
    wgp=$(wg_port)

    local virt wgkernel=false
    virt=$(detect_virt)
    if wg_kernel_ok; then wgkernel=true; fi

    local cw=false cx=false cwe=false cts=false ifaces
    if conflict_warp; then cw=true; fi
    if conflict_x3ui; then cx=true; fi
    if conflict_wgeasy; then cwe=true; fi
    if conflict_tailscale; then cts=true; fi
    ifaces=$(other_wg_ifaces_csv)

    d_bool installed "$installed"
    [ -n "$version" ] && d_str version "$version"
    [ -n "$sha" ] && d_str bin_sha256 "$sha"
    d_bool running "$running"
    if [ "$running" = true ]; then
        d_str mode "$mode"
        d_str obf "$obf"
    fi
    d_str runtime "$runtime"
    d_num euid "$euid"

    local wgobj
    if [ -n "$wgp" ]; then
        wgobj=$(printf '{"present":%s,"port":%s}' "$wgpresent" "$wgp")
    else
        wgobj=$(printf '{"present":%s,"port":null}' "$wgpresent")
    fi
    d_raw wg "$wgobj"

    d_str virt "$virt"
    d_bool wg_kernel "$wgkernel"
    d_raw conflicts "$(printf '{"warp":%s,"x3ui":%s,"wgeasy":%s,"tailscale":%s,"other_ifaces":[%s]}' \
        "$cw" "$cx" "$cwe" "$cts" "$ifaces")"

    ok
}

# Идемпотентный бутстрап ft-wg0; чужой WG не трогаем. Требует root.
cmd_wg_setup() {
    stage wg_setup
    [ -n "$ARG_WG_PORT" ]     || fail bad_arg "--port required"
    [ -n "$ARG_WG_ENDPOINT" ] || fail bad_arg "--endpoint required"
    [ "$(id -u 2>/dev/null || echo -1)" -eq 0 ] || fail needs_root "wireguard setup requires root"
    with_lock

    local existed=false
    if wg_present; then
        wg_is_ours || fail conf_rewrite_failed \
            "foreign $WG_CONF exists (no '$WG_MARKER' marker); refusing to modify"
        existed=true
    fi

    wg_ensure

    local port
    port=$(wg_port)
    [ -n "$port" ] || port="$ARG_WG_PORT"

    d_raw wg "$(printf '{"port":%s,"existed":%s}' "$port" "$existed")"
    _emit_client_conf
    ok
}

cmd_install() {
    stage install
    [ "$(id -u 2>/dev/null || echo -1)" -eq 0 ] || fail needs_root "install requires root"
    mkdir -p "$PREFIX" || fail not_writable "cannot create $PREFIX"
    [ -w "$PREFIX" ] || fail not_writable "$PREFIX not writable"

    local name bin latest_url asset_url tmp ver curver="" cached=0
    name=$(detect_arch) || fail unsupported_arch "unsupported arch: $(uname -m)"
    bin="$PREFIX/$name"
    latest_url="$BASE_URL/$name"
    [ -f "$VERFILE" ] && curver=$(cat "$VERFILE" 2>/dev/null || true)

    log "resolving latest version"
    ver=$(_resolve_version "$latest_url")
    if [ -z "$ver" ]; then
        if [ -x "$bin" ]; then
            log "version resolve failed; using installed binary"
            ver="${curver:-installed}"; cached=1
        else
            fail version_resolve_failed "cannot resolve latest version (no Location from $BASE_URL)"
        fi
    elif [ -x "$bin" ] && [ "$ver" = "$curver" ]; then
        cached=1
    fi

    # Качаем по тегу (не latest): релиз мог переехать между резолвом и загрузкой.
    asset_url="$RELEASES_URL/download/$ver/$name"
    if [ "$cached" = 0 ]; then
        log "downloading $name @ $ver"
        tmp=$(mktemp "$bin.XXXXXX" 2>/dev/null) || tmp="$bin.new.$$"
        if ! _dl "$asset_url" "$tmp"; then
            if ! _dl "$latest_url" "$tmp"; then rm -f "$tmp"; fail download_failed "binary download failed"; fi
        fi
        _verify_download "$tmp" "$ARG_SHA256"
        chmod +x "$tmp"
        with_lock
        [ -f "$bin" ] && cp -f "$bin" "$bin.bak" 2>/dev/null || true
        mv -f "$tmp" "$bin"
        echo "$ver" > "$VERFILE"
    else
        with_lock
    fi

    local was_running=false
    if rt_running; then was_running=true; fi

    if has_systemd; then
        if [ -f "$PIDFILE" ]; then log "killing legacy nohup before systemd takeover"; _kill_legacy_nohup; fi
        _install_systemd_unit
        state_set runtime systemd
    else
        state_set runtime nohup
    fi

    local stg=downloaded
    [ "$cached" = 1 ] && stg=cached
    d_str stage "$stg"
    d_str bin "$name"
    d_str version "$ver"
    d_str runtime "$(current_runtime)"
    if [ "$was_running" = true ] && [ "$cached" = 0 ]; then d_bool needs_restart true; else d_bool needs_restart false; fi
    ok
}

cmd_start() {
    stage start
    [ -n "$ARG_LISTEN" ]  || fail bad_arg "--listen required"
    [ -n "$ARG_CONNECT" ] || fail bad_arg "--connect required"
    local bin
    bin=$(_bin_path)
    { [ -n "$bin" ] && [ -x "$bin" ]; } || fail not_installed "server binary not installed; run install first"
    with_lock

    # Владелец в allowlist ДО запуска с -clients-file и ДО остановки сервера
    # (старый бинарь без сабкоманды clients умрёт тут, не уронив рабочий процесс).
    if [ -n "$ARG_CLIENT_ID" ]; then
        clients_add "$ARG_CLIENT_ID" "owner"
        printf '%s\n' "$ARG_CLIENT_ID" > "$OWNERCIDFILE"
        chmod 600 "$OWNERCIDFILE"
    fi

    # Порт занят ЧУЖИМ процессом? Честный отказ - НЕ убиваем (в отличие от fuser).
    # Проверка ДО rt_stop: fail после остановки оставил бы сервер лежать.
    # Свой процесс на порту - не конфликт (ниже он же перезапускается).
    local port proto=udp owner opid
    port=${ARG_LISTEN##*:}
    if [[ "$port" =~ ^[0-9]+$ ]]; then
        owner=$(port_owner "$proto" "$port")
        case "$owner" in
            free|unknown) : ;;
            *)
                opid=$(port_pid "$proto" "$port")
                if [ -z "$opid" ] || ! pid_is_ours "$opid"; then
                    fail listen_port_busy "$proto port $port busy (owner: $owner)"
                fi ;;
        esac
        _open_firewall "$port"
    fi

    # Останавливаем СВОЙ процесс (освобождает порт), чистим legacy nohup.
    rt_stop
    _kill_legacy_nohup

    rt_start "$bin"
    ok
}

cmd_stop() {
    stage stop
    with_lock
    rt_stop
    d_bool stopped true
    ok
}

cmd_logs() {
    stage logs
    local l
    case "$(current_runtime)" in
        systemd)
            if command -v journalctl >/dev/null 2>&1; then
                while IFS= read -r l; do log "$l"; done \
                    < <(journalctl -u "$UNIT_NAME" -n "$ARG_TAIL" --no-pager --output=cat 2>/dev/null)
            else
                log "(journalctl unavailable)"
            fi ;;
        nohup)
            if [ -f "$LOGFILE" ]; then
                while IFS= read -r l; do log "$l"; done < <(tail -n "$ARG_TAIL" "$LOGFILE")
            else
                log "(log empty)"
            fi ;;
    esac
    ok
}

cmd_share_info() { stage share_info; share_info_emit; ok; }

cmd_share_list() {
    stage share_list
    d_raw peers "$(peers_json)"
    local sp cj
    sp=$(_self_pub)
    if [ -n "$sp" ]; then d_str self_pub "$sp"; fi
    cj=$(clients_json) || fail clients_cmd_failed "clients list failed"
    d_raw clients "$cj"
    ok
}

cmd_peer_add() {
    stage peer_add
    [ -n "$ARG_NAME_B64" ]    || fail bad_arg "--name-b64 required"
    [ -n "$ARG_WG_ENDPOINT" ] || fail bad_arg "--endpoint required"
    command -v wg >/dev/null 2>&1     || fail wg_tools_missing "wireguard-tools not installed"
    command -v base64 >/dev/null 2>&1 || fail base64_missing "base64 not available on server"
    with_lock
    { wg_present && wg_is_ours; } || fail no_wg_backend "no managed wireguard backend"
    mkdir -p "$SHARE_DIR"; chmod 700 "$SHARE_DIR"

    # allowlist сначала: его откат (clients remove) дешевле выкусывания пира.
    if [ -n "$ARG_CLIENT_ID" ]; then clients_add "$ARG_CLIENT_ID" "$(_name_from_b64 "$ARG_NAME_B64")"; fi
    with_peers_lock

    local tmp stored
    tmp=$(mktemp "$SHARE_DIR/.new.XXXXXX" 2>/dev/null) || tmp=""
    if [ -z "$tmp" ]; then
        [ -n "$ARG_CLIENT_ID" ] && clients_remove_soft "$ARG_CLIENT_ID" || true
        fail peer_add_failed "mktemp failed"
    fi
    if ! _wg_new_peer "$WG_CONF" "$tmp" "# ft-user: $ARG_NAME_B64"; then
        rm -f "$tmp"
        [ -n "$ARG_CLIENT_ID" ] && clients_remove_soft "$ARG_CLIENT_ID" || true
        fail peer_add_failed "cannot add peer to $WG_CONF"
    fi
    stored="$SHARE_DIR/$(_pub_fs "$NEW_PEER_PUB").conf"
    mv -f "$tmp" "$stored"; chmod 600 "$stored"

    if [ -n "$ARG_CLIENT_ID" ]; then
        printf '%s\n' "$ARG_CLIENT_ID" > "$SHARE_DIR/$(_pub_fs "$NEW_PEER_PUB").cid"
        chmod 600 "$SHARE_DIR/$(_pub_fs "$NEW_PEER_PUB").cid"
        d_str client_id "$ARG_CLIENT_ID"
    fi
    d_raw peer "$(printf '{"pub":"%s","ip":"%s"}' "$(esc "$NEW_PEER_PUB")" "$(esc "$NEW_PEER_IP")")"
    d_str client_conf_b64 "$(base64 < "$stored" | tr -d '\n')"
    ok
}

cmd_peer_conf() {
    stage peer_conf
    [ -n "$ARG_PUBKEY" ]              || fail bad_arg "--pubkey required"
    command -v base64 >/dev/null 2>&1 || fail base64_missing "base64 not available on server"
    local stored cidfile cid=""
    stored="$SHARE_DIR/$(_pub_fs "$ARG_PUBKEY").conf"
    [ -f "$stored" ] || fail no_stored_conf "no stored conf for this peer"
    cidfile="$SHARE_DIR/$(_pub_fs "$ARG_PUBKEY").cid"
    [ -f "$cidfile" ] && cid=$(tr -d ' \r\n' < "$cidfile") || true
    # Пир без cid (выдан до allowlist): регистрируем переданный кандидат.
    if [ -z "$cid" ] && [ -n "$ARG_CLIENT_ID" ]; then
        clients_add "$ARG_CLIENT_ID" "$(_name_from_b64 "$ARG_NAME_B64")"
        printf '%s\n' "$ARG_CLIENT_ID" > "$cidfile"; chmod 600 "$cidfile"; cid="$ARG_CLIENT_ID"
    fi
    if [ -n "$cid" ]; then d_str client_id "$cid"; fi
    d_str client_conf_b64 "$(base64 < "$stored" | tr -d '\n')"
    ok
}

cmd_peer_remove() {
    stage peer_remove
    [ -n "$ARG_PUBKEY" ]          || fail bad_arg "--pubkey required"
    command -v wg >/dev/null 2>&1 || fail wg_tools_missing "wireguard-tools not installed"
    with_lock
    { wg_present && wg_is_ours; } || fail no_wg_backend "no managed wireguard backend"

    local self_pub cidfile
    self_pub=$(_self_pub)
    if [ -n "$self_pub" ] && [ "$ARG_PUBKEY" = "$self_pub" ]; then fail bad_arg "cannot remove owner peer"; fi

    # Отзыв cid в обеих ветках (идемпотентный повтор при упавшем прошлом вызове).
    cidfile="$SHARE_DIR/$(_pub_fs "$ARG_PUBKEY").cid"
    if [ -f "$cidfile" ]; then clients_remove_soft "$(tr -d ' \r\n' < "$cidfile")"; rm -f "$cidfile"; fi

    if ! _peer_in_conf "$WG_CONF" "$ARG_PUBKEY"; then d_bool removed false; ok; return; fi
    # adopted без client conf: владельца не опознать -> удаляем только ft-user-пиры.
    if [ -z "$self_pub" ] && ! _peer_marked "$WG_CONF" "$ARG_PUBKEY"; then
        fail bad_arg "cannot remove unmanaged peer"
    fi

    with_peers_lock
    local tmp="$WG_CONF.tmp"
    if ! _cut_peer_block "$WG_CONF" "$ARG_PUBKEY" "$tmp"; then rm -f "$tmp"; fail conf_rewrite_failed "conf rewrite failed"; fi
    chmod 600 "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$WG_CONF"
    wg_reconcile || true
    rm -f "$SHARE_DIR/$(_pub_fs "$ARG_PUBKEY").conf"
    d_bool removed true
    ok
}

# Прокси-бэкенд: гость только в allowlist, без WG-пира.
cmd_client_add() {
    stage client_add
    [ -n "$ARG_CLIENT_ID" ] || fail bad_arg "--client-id required"
    [ -n "$ARG_NAME_B64" ]  || fail bad_arg "--name-b64 required"
    with_lock
    clients_add "$ARG_CLIENT_ID" "$(_name_from_b64 "$ARG_NAME_B64")"
    ok
}

cmd_client_remove() {
    stage client_remove
    [ -n "$ARG_CLIENT_ID" ] || fail bad_arg "--client-id required"
    with_lock
    if [ -f "$OWNERCIDFILE" ] && [ "$(tr -d ' \r\n' < "$OWNERCIDFILE")" = "$ARG_CLIENT_ID" ]; then
        fail bad_arg "cannot remove owner client id"
    fi
    local bin
    bin=$(_bin_path)
    { [ -n "$bin" ] && [ -x "$bin" ]; } || fail not_installed "server binary not installed"
    CLIENTS_FILE="$CLIENTSFILE" "$bin" clients remove "$ARG_CLIENT_ID" >/dev/null \
        || fail clients_cmd_failed "clients remove failed"
    ok
}

# Деструктив, сносим только своё. Пакет wireguard-tools - лишь при --with-wg-pkg
# и если ставили мы. Живой ip_forward не трогаем.
_emit_uninstall() {  # DRY RBIN RUNIT RWG RPREFIX RSYSCTL RUFW RLEGACY WGPKG KEPT
    d_bool dry_run "$1"
    d_raw removed "$(printf '{"binary":%s,"unit":%s,"wg_iface":%s,"prefix":%s,"sysctl":%s,"ufw":%s,"legacy_unit":%s}' \
        "$2" "$3" "$4" "$5" "$6" "$7" "$8")"
    d_bool wg_pkg_removed "$9"
    d_raw kept "[${10}]"
}

cmd_uninstall() {
    stage uninstall
    [ "$(id -u 2>/dev/null || echo -1)" -eq 0 ] || fail needs_root "uninstall requires root"
    with_lock

    local r_bin=false r_unit=false r_wg=false r_prefix=false r_sysctl=false r_ufw=false r_legacy=false
    local wgpkg=false kept=""
    local bin sysctl_drop="/etc/sysctl.d/99-free-turn-proxy-wg.conf"
    bin=$(_bin_path)
    _kept_add() { if [ -z "$kept" ]; then kept="\"$1\""; else kept="$kept,\"$1\""; fi; }

    if [ -n "$bin" ] && [ -x "$bin" ]; then r_bin=true; fi
    if [ -f "$UNIT_PATH" ]; then r_unit=true; fi
    if [ -d "$PREFIX" ]; then r_prefix=true; fi
    if [ -f "$sysctl_drop" ]; then r_sysctl=true; fi
    if wg_present; then
        if wg_is_ours; then r_wg=true; else _kept_add foreign_ft_wg0; fi
    fi
    if has_systemd && systemctl list-unit-files 2>/dev/null | grep -q "^$LEGACY_UNIT"; then r_legacy=true; fi

    if [ "$ARG_WITH_WG_PKG" = 1 ] && [ "$(state_get wg_pkg_owned)" = 1 ]; then
        wgpkg=true
    elif command -v wg >/dev/null 2>&1; then
        _kept_add wireguard-tools
    fi
    _kept_add ip_forward

    if [ "$ARG_DRY_RUN" = 1 ]; then
        _emit_uninstall true "$r_bin" "$r_unit" "$r_wg" "$r_prefix" "$r_sysctl" "$r_ufw" "$r_legacy" "$wgpkg" "$kept"
        ok; return
    fi

    # порядок: stop ядра -> unit/legacy -> ufw -> wg(PostDown снимает NAT) -> sysctl -> пакет -> PREFIX
    rt_stop
    if has_systemd; then
        systemctl disable "$UNIT_NAME" >/dev/null 2>&1 || true
        systemctl stop "$LEGACY_UNIT" >/dev/null 2>&1 || true
        systemctl disable "$LEGACY_UNIT" >/dev/null 2>&1 || true
    fi
    pkill -9 -f "vk-turn-proxy" 2>/dev/null || true
    rm -f "$UNIT_PATH" "$LAUNCHER"
    has_systemd && systemctl daemon-reload >/dev/null 2>&1 || true

    local lport=""
    if [ -f "$ARGSFILE" ]; then
        lport=$(awk 'prev=="-listen"{print;exit}{prev=$0}' "$ARGSFILE"); lport=${lport##*:}
    fi
    if [[ "$lport" =~ ^[0-9]+$ ]] && command -v ufw >/dev/null 2>&1 \
        && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw delete allow "${lport}/udp" >/dev/null 2>&1 || true
        r_ufw=true
    fi

    if [ "$r_wg" = true ]; then
        # disable --now (не голый disable): иначе юнит остаётся active (RemainAfterExit)
        # и enable --now при переустановке - no-op без интерфейса.
        has_systemd && systemctl disable --now "wg-quick@$WG_IFACE" >/dev/null 2>&1 || true
        wg-quick down "$WG_IFACE" >/dev/null 2>&1 || ip link delete "$WG_IFACE" >/dev/null 2>&1 || true
        rm -f "$WG_CONF"
    fi

    rm -f "$sysctl_drop"   # живой ip_forward НЕ трогаем

    if [ "$wgpkg" = true ]; then pkg_remove wireguard-tools wireguard-go boringtun || true; fi

    rm -rf "$PREFIX"

    _emit_uninstall false "$r_bin" "$r_unit" "$r_wg" "$r_prefix" "$r_sysctl" "$r_ufw" "$r_legacy" "$wgpkg" "$kept"
    ok
}

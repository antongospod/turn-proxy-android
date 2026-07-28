# Владеем ТОЛЬКО ft-wg0, чужой WG не трогаем. Сеть /24: .1 сервер, .2 владелец, .3+ гости.
# Правка conf: wg-quick strip -> wg syncconf (без рестарта, текст==live).

# Интерфейс прошлой версии (без маркера, та же подсеть 10.13.13) - мигрируем в ft-wg0.
LEGACY_WG_IFACE="wg0"
LEGACY_WG_CONF="$WG_DIR/$LEGACY_WG_IFACE.conf"

wg_present() { [ -f "$WG_CONF" ]; }

# Conf несёт маркер владения? Защита от чужого файла с тем же именем.
wg_is_ours() {
    [ -f "$WG_CONF" ] || return 1
    grep -qF "$WG_MARKER" "$WG_CONF" 2>/dev/null
}

# Порт НАШЕГО интерфейса (живой listen-port, иначе ListenPort из conf). Пусто -
# бэкенда нет. НЕ `wg show all` (там мог быть WARP).
wg_port() {
    local p=""
    if command -v wg >/dev/null 2>&1; then
        p=$(wg show "$WG_IFACE" listen-port 2>/dev/null | tr -d ' \r' || true)
        case "$p" in ''|0) p="" ;; esac
        if [ -n "$p" ]; then echo "$p"; return 0; fi
    fi
    if [ -f "$WG_CONF" ]; then
        p=$(sed -n 's/^[[:space:]]*ListenPort[[:space:]]*=[[:space:]]*//p' "$WG_CONF" 2>/dev/null \
            | head -n1 | sed 's/[#;].*//' | tr -d ' \r')
        case "$p" in ''|*[!0-9]*) p="" ;; esac
        # if, не &&-хвост: пустой порт вернул бы 1 - set -e убьёт $(wg_port) у вызывающего.
        if [ -n "$p" ]; then echo "$p"; fi
    fi
}

# echo userspace-реализацию для WG_QUICK_USERSPACE_IMPLEMENTATION или код 1.
_wg_userspace_bin() {
    if command -v wireguard-go  >/dev/null 2>&1; then echo wireguard-go;  return 0; fi
    if command -v boringtun-cli >/dev/null 2>&1; then echo boringtun-cli; return 0; fi
    if command -v boringtun     >/dev/null 2>&1; then echo boringtun;     return 0; fi
    return 1
}

_wg_tools_ensure() {
    if ! command -v wg >/dev/null 2>&1 || ! command -v wg-quick >/dev/null 2>&1; then
        log "installing wireguard-tools"
        pkg_install wireguard-tools || pkg_install wireguard || true
        if command -v wg >/dev/null 2>&1 && command -v wg-quick >/dev/null 2>&1; then
            state_set wg_pkg_owned 1
        else
            fail wg_tools_missing "wireguard-tools install failed (pkg manager: $(pkg_mgr || echo none))"
        fi
    fi
    # Нет kernel-модуля (openvz/lxc) - ставим userspace.
    if ! wg_kernel_ok; then
        if ! _wg_userspace_bin >/dev/null 2>&1; then
            log "no kernel wireguard module; installing userspace impl"
            pkg_install wireguard-go || pkg_install boringtun || true
        fi
        _wg_userspace_bin >/dev/null 2>&1 \
            || fail wg_userspace_missing "no kernel wireguard and no userspace impl (virt: $(detect_virt))"
    fi
}

_wg_alloc_ip() {  # CONF -> свободный хост или код 1
    local conf=$1 addr base used i
    addr=$(sed -n 's/^[[:space:]]*Address[[:space:]]*=[[:space:]]*//p' "$conf" \
        | head -n1 | cut -d, -f1 | cut -d/ -f1 | tr -d ' \r')
    base=${addr%.*}
    [ -n "$base" ] && [ "$base" != "$addr" ] || return 1   # только IPv4
    used=$(printf '%s\n' "$addr"
           sed -n 's/^[[:space:]]*AllowedIPs[[:space:]]*=[[:space:]]*//p' "$conf" \
               | tr ',' '\n' | cut -d/ -f1 | tr -d ' ')
    for i in $(seq 2 254); do
        if ! printf '%s\n' "$used" | grep -qx "$base.$i"; then echo "$base.$i"; return 0; fi
    done
    return 1
}

wg_reconcile() {
    command -v wg >/dev/null 2>&1 || return 0
    ip link show "$WG_IFACE" >/dev/null 2>&1 || return 0   # не поднят - синкать нечего
    if wg syncconf "$WG_IFACE" <(wg-quick strip "$WG_IFACE") 2>/dev/null; then return 0; fi
    wg-quick strip "$WG_IFACE" 2>/dev/null | wg syncconf "$WG_IFACE" /dev/stdin 2>/dev/null
}

_wg_write_client_conf() {  # PRIV ADDR SRVPUB  -> $WG_CLIENT_CONF
    ( umask 077
      cat > "$WG_CLIENT_CONF" <<EOF
[Interface]
PrivateKey = $1
Address = $2/32
MTU = $WG_MTU
DNS = $ARG_DNS

[Peer]
PublicKey = $3
AllowedIPs = 0.0.0.0/0
Endpoint = $ARG_WG_ENDPOINT
PersistentKeepalive = 25
EOF
    )
}

# Сохранённый клиентский conf валиден против текущего серверного conf:
# его ключ числится пиром, серверный pub совпадает. Иначе протух.
_wg_client_conf_valid() {  # SERVER_CONF
    local conf=$1 cp cpub sp spub conf_spub
    [ -f "$WG_CLIENT_CONF" ] || return 1
    cp=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ -n "$cp" ] || return 1
    cpub=$(printf '%s' "$cp" | wg pubkey 2>/dev/null) || return 1
    sed -n 's/^[[:space:]]*PublicKey[[:space:]]*=[[:space:]]*//p' "$conf" | tr -d ' \r' \
        | grep -qxF "$cpub" || return 1
    sp=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$conf" | head -n1 | tr -d ' \r')
    [ -n "$sp" ] || return 1
    spub=$(printf '%s' "$sp" | wg pubkey 2>/dev/null) || return 1
    conf_spub=$(sed -n 's/^[[:space:]]*PublicKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ "$conf_spub" = "$spub" ]
}

# Откат conf из .bak при битом результате. Возврат - в NEW_PEER_PUB/NEW_PEER_IP.
NEW_PEER_PUB=""
NEW_PEER_IP=""
_wg_new_peer() {  # CONF OUT MARKER
    local conf=$1 out=$2 marker=$3
    local sp spub ip cp cpub bak
    sp=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$conf" | head -n1 | tr -d ' \r')
    [ -n "$sp" ] || return 1
    spub=$(printf '%s' "$sp" | wg pubkey 2>/dev/null) || return 1
    ip=$(_wg_alloc_ip "$conf") || return 1
    [ -n "$ip" ] || return 1
    cp=$(wg genkey 2>/dev/null) || return 1
    cpub=$(printf '%s' "$cp" | wg pubkey 2>/dev/null) || return 1

    bak="$conf.bak"
    cp -f "$conf" "$bak" 2>/dev/null || true
    cat >> "$conf" <<EOF

[Peer]
$marker
PublicKey = $cpub
AllowedIPs = $ip/32
EOF
    if command -v wg-quick >/dev/null 2>&1 && ! wg-quick strip "$WG_IFACE" >/dev/null 2>&1; then
        [ -f "$bak" ] && mv -f "$bak" "$conf"
        return 1
    fi
    rm -f "$bak"
    chmod 600 "$conf" 2>/dev/null || true
    wg_reconcile || true

    local saved_out=$out
    WG_CLIENT_CONF_SAVE="$WG_CLIENT_CONF"
    WG_CLIENT_CONF="$saved_out"
    _wg_write_client_conf "$cp" "$ip" "$spub"
    WG_CLIENT_CONF="$WG_CLIENT_CONF_SAVE"

    NEW_PEER_PUB="$cpub"
    NEW_PEER_IP="$ip"
    return 0
}

# Egress-интерфейс: поле после "dev", НЕ $5 (роут без "via" - "default dev eth0
# scope link" - сдвигает поля, $5 давал "kernel" -> MASQUERADE в никуда, нет NAT).
# route get надёжнее show default (несколько дефолтов/policy routing).
_wg_default_wan() {
    local wan
    wan=$(ip route get 1.1.1.1 2>/dev/null \
        | awk '{for(i=1;i<NF;i++) if($i=="dev"){print $(i+1); exit}}')
    if [ -n "$wan" ]; then echo "$wan"; return 0; fi
    ip route show default 2>/dev/null \
        | awk '{for(i=1;i<NF;i++) if($i=="dev"){print $(i+1); exit}}'
}

_wg_up() {
    local impl=""
    if ! wg_kernel_ok; then
        impl=$(_wg_userspace_bin) || fail wg_userspace_missing "no userspace wireguard"
        export WG_QUICK_USERSPACE_IMPLEMENTATION="$impl"
    fi
    if ip link show "$WG_IFACE" >/dev/null 2>&1; then
        # Живой интерфейс НЕ трогаем: down при идемпотентном re-setup рвал бы
        # туннели всех гостей. Изменения conf доедет wg_reconcile у вызывающего.
        if wg show "$WG_IFACE" >/dev/null 2>&1; then
            has_systemd && systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || true
            return 0
        fi
        # Полуподнятый интерфейс прошлой неудачи -> "already exists". Сносим.
        wg-quick down "$WG_IFACE" >/dev/null 2>&1 \
            || ip link delete "$WG_IFACE" >/dev/null 2>&1 || true
    fi
    [ -z "$impl" ] && modprobe wireguard >/dev/null 2>&1 || true

    if has_systemd; then
        # После ручного wg-quick down юнит висит active (RemainAfterExit) и
        # enable --now становится no-op: "успех" без интерфейса. Сбрасываем stop'ом
        # (сюда попадаем только с мёртвым интерфейсом) и проверяем результат по wg show.
        systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || true
        if systemctl enable --now "wg-quick@$WG_IFACE" >/dev/null 2>&1 \
            && wg show "$WG_IFACE" >/dev/null 2>&1; then
            return 0
        fi
    fi
    local out=""
    if ! out=$(wg-quick up "$WG_IFACE" 2>&1); then
        log "wg-quick: $(printf '%s' "$out" | tail -n 3 | tr '\n' ';')"
        return 1
    fi
    has_systemd && systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || true
    return 0
}

_wg_port_free_check() {
    local owner
    owner=$(port_owner udp "$ARG_WG_PORT")
    case "$owner" in
        free|unknown) : ;;
        *) fail wg_port_busy "udp port $ARG_WG_PORT busy (owner: $owner)" ;;
    esac
}

# MSS clamp для TCP через туннель. -o: ответный SYN-ACK из инета несёт MSS 1460,
# режем по PMTU интерфейса. -i: страховка от клиента, забывшего MTU - ограничиваем
# то, что ему будут слать. ACTION - -A (PostUp) или -D (PostDown); IFACE - имя или
# %i для conf (wg-quick подставит сам).
_wg_mss_rule() {  # ACTION IFACE -> строка iptables-команд
    printf 'iptables -t mangle %s FORWARD -o %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu; ' "$1" "$2"
    printf 'iptables -t mangle %s FORWARD -i %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss %s' "$1" "$2" "$((WG_MTU - 40))"
}

_wg_create_fresh() {
    local wan postup="" postdown="" sp spub cp cpub kd
    echo 'net.ipv4.ip_forward = 1' > /etc/sysctl.d/99-free-turn-proxy-wg.conf 2>/dev/null || true
    sysctl -qw net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

    wan=$(_wg_default_wan)
    if [ -n "$wan" ] && ! command -v iptables >/dev/null 2>&1; then
        log "iptables not found; NAT rules skipped"
        wan=""
    fi
    [ -z "$wan" ] && log "WAN interface not detected; NAT skipped"
    if [ -n "$wan" ]; then
        postup="PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o $wan -j MASQUERADE; $(_wg_mss_rule -A '%i')"
        postdown="PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o $wan -j MASQUERADE; $(_wg_mss_rule -D '%i')"
    fi

    kd=$(mktemp -d 2>/dev/null) || fail internal "mktemp -d failed"
    ( umask 077; wg genkey > "$kd/s"; wg genkey > "$kd/c" )
    sp=$(cat "$kd/s"); spub=$(wg pubkey < "$kd/s")
    cp=$(cat "$kd/c"); cpub=$(wg pubkey < "$kd/c")

    ( umask 077
      cat > "$WG_CONF" <<EOF
[Interface]
$WG_MARKER
Address = $WG_NET.1/24
ListenPort = $ARG_WG_PORT
MTU = $WG_MTU
PrivateKey = $sp
$postup
$postdown

[Peer]
# owner (client conf: $WG_CLIENT_CONF)
PublicKey = $cpub
AllowedIPs = $WG_NET.2/32
EOF
    )
    chmod 600 "$WG_CONF"
    _wg_write_client_conf "$cp" "$WG_NET.2" "$spub"

    if command -v shred >/dev/null 2>&1; then shred -u "$kd/s" "$kd/c" 2>/dev/null || true; fi
    rm -rf "$kd"
}

# Владелец потерял клиентский conf (переустановка приложения) - выдаём новый
# пир. Старый .2 остаётся осиротевшим (приватник владельца не хранится).
_wg_ensure_owner_client() {
    if _wg_client_conf_valid "$WG_CONF"; then
        wg_reconcile || true
        return 0
    fi
    log "owner client conf missing/stale - regenerating peer"
    rm -f "$WG_CLIENT_CONF"
    _wg_new_peer "$WG_CONF" "$WG_CLIENT_CONF" "# owner" \
        || fail peer_add_failed "cannot add owner peer to $WG_CONF"
}

# В conf есть хоть один пир, выданный приложением (маркер ft-user) -> это наш install.
_conf_has_guests() { grep -q '^[[:space:]]*# ft-user: ' "$1" 2>/dev/null; }

# server-pubkey conf (из Interface PrivateKey). Код 1 - нет ключа/wg.
_conf_server_pub() {
    local sp
    sp=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$1" | head -n1 | tr -d ' \r')
    [ -n "$sp" ] || return 1
    printf '%s' "$sp" | wg pubkey 2>/dev/null
}

_confs_same_server() {  # A B -> 0 если одинаковый server-key
    local a b
    a=$(_conf_server_pub "$1") || return 1
    b=$(_conf_server_pub "$2") || return 1
    [ -n "$a" ] && [ "$a" = "$b" ]
}

# wg0.conf - НАШ старый install? Любой из сигналов: пир с ft-user-маркером;
# server-pub числится Peer-ом в нашем client conf (clean-случай до _wg_create_fresh);
# Address в нашей дефолтной подсети (10.13.13 - только наша, коллизия = наша).
_legacy_is_ours() {
    [ -f "$LEGACY_WG_CONF" ] || return 1
    _conf_has_guests "$LEGACY_WG_CONF" && return 0
    if [ -f "$WG_CLIENT_CONF" ] && command -v wg >/dev/null 2>&1; then
        local lspub cpeer
        lspub=$(_conf_server_pub "$LEGACY_WG_CONF" || true)
        cpeer=$(sed -n 's/^[[:space:]]*PublicKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
        [ -n "$lspub" ] && [ "$lspub" = "$cpeer" ] && return 0
    fi
    local addr base
    addr=$(sed -n 's/^[[:space:]]*Address[[:space:]]*=[[:space:]]*//p' "$LEGACY_WG_CONF" | head -n1 | cut -d, -f1 | cut -d/ -f1 | tr -d ' \r')
    base=${addr%.*}
    [ -n "$base" ] && [ "$base" = "$WG_NET" ]
}

_iface_down() {  # снять интерфейс (conf не трогаем)
    has_systemd && systemctl disable --now "wg-quick@$1" >/dev/null 2>&1 || true
    wg-quick down "$1" >/dev/null 2>&1 || ip link delete "$1" >/dev/null 2>&1 || true
}

# Сделать ft-wg0.conf из wg0.conf (+маркер владения). Сборка/валидация tmp ДО
# сноса интерфейсов - битый результат не оставит систему без conf. Код 1 - провал.
_adopt_to_ftwg0() {
    local tmp="$WG_CONF.mig"
    if grep -qF "$WG_MARKER" "$LEGACY_WG_CONF"; then
        cp -f "$LEGACY_WG_CONF" "$tmp" || { rm -f "$tmp"; return 1; }
    else
        awk -v m="$WG_MARKER" '{print} !d && /^[[:space:]]*\[Interface\]/{print m; d=1}' \
            "$LEGACY_WG_CONF" > "$tmp" || { rm -f "$tmp"; return 1; }
    fi
    [ -s "$tmp" ] || { rm -f "$tmp"; return 1; }
    chmod 600 "$tmp"
    _iface_down "$WG_IFACE"          # мог быть забагованный split-brain ft-wg0
    _iface_down "$LEGACY_WG_IFACE"
    mv -f "$tmp" "$WG_CONF"          # _wg_up у вызывающего поднимет ft-wg0 с новым conf
    rm -f "$LEGACY_WG_CONF"
}

# Прошлая версия держала интерфейс wg0 на той же подсети, что и ft-wg0 -> два
# интерфейса на 10.13.13.1/24 = коллизия connected-route (туннель без интернета)
# + пиры гостей в wg0, невидимы новому app. Конфиги гостей валидны против
# server-key wg0, поэтому при усыновлении сохраняем ИМЕННО его ключ (owner
# переген-ся из _wg_ensure_owner_client). Ветки:
#   - ft-wg0 нет                      -> усыновляем wg0 (clean-апгрейд);
#   - тот же server-key               -> wg0 дубль, сносим;
#   - гости в wg0, ft-wg0 пуст        -> wg0 источник правды, заменяем им ft-wg0;
#   - гости на обоих, ключи разные    -> лоссы неизбежны, НЕ трогаем (админ решит);
#   - иначе (wg0 без гостей)          -> лишний, сносим.
# Чужой wg0 не трогаем. До _wg_create_fresh (тот затрёт WG_CLIENT_CONF).
_wg_migrate_legacy() {
    [ -f "$LEGACY_WG_CONF" ] || return 0
    command -v wg >/dev/null 2>&1 || return 0
    _legacy_is_ours || return 0

    if [ ! -f "$WG_CONF" ]; then
        log "adopting legacy $LEGACY_WG_IFACE as $WG_IFACE (preserving peers)"
        _adopt_to_ftwg0 || return 0
    elif _confs_same_server "$LEGACY_WG_CONF" "$WG_CONF"; then
        log "removing redundant legacy $LEGACY_WG_IFACE"
        _iface_down "$LEGACY_WG_IFACE"; rm -f "$LEGACY_WG_CONF"
    elif _conf_has_guests "$LEGACY_WG_CONF" && ! _conf_has_guests "$WG_CONF"; then
        log "split-brain: adopting legacy $LEGACY_WG_IFACE peers over empty $WG_IFACE"
        _adopt_to_ftwg0 || return 0
    elif _conf_has_guests "$LEGACY_WG_CONF"; then
        log "split-brain guests on both $LEGACY_WG_IFACE and $WG_IFACE; left as-is"
        return 0
    else
        log "removing redundant legacy $LEGACY_WG_IFACE"
        _iface_down "$LEGACY_WG_IFACE"; rm -f "$LEGACY_WG_CONF"
    fi
    sysctl -qw net.ipv4.ip_forward=1 >/dev/null 2>&1 || true
}

# Вставить строку в [Interface] (после заголовка секции). Возврат 1 - conf не тронут.
_wg_insert_interface_line() {  # CONF LINE
    local tmp="$1.mig"
    awk -v m="$2" '{print} !d && /^[[:space:]]*\[Interface\]/{print m; d=1}' "$1" > "$tmp" \
        || { rm -f "$tmp"; return 1; }
    [ -s "$tmp" ] || { rm -f "$tmp"; return 1; }
    chmod 600 "$tmp"
    mv -f "$tmp" "$1"
}

# У conf есть MTU в [Interface] (до первого [Peer])?
_wg_conf_has_mtu() {  # CONF
    [ -f "$1" ] || return 1
    sed -n '/^[[:space:]]*\[Peer\]/q; p' "$1" | grep -qiE '^[[:space:]]*MTU[[:space:]]*='
}

# TODO: миграция серверов, поднятых до появления WG_MTU/MSS clamp. Правки применяются
# вживую (down/up рвал бы туннели гостей), conf доедет при следующем старте.
# Удалить, когда все установки пройдут через актуальный _wg_create_fresh.
_wg_migrate_mtu() {
    wg_is_ours || return 0

    if ! _wg_conf_has_mtu "$WG_CONF"; then
        _wg_insert_interface_line "$WG_CONF" "MTU = $WG_MTU" \
            && log "migrated: MTU = $WG_MTU -> $WG_CONF"
    fi
    # Клиентский conf валиден - _wg_ensure_owner_client его не переген-т, дописываем сами.
    if [ -f "$WG_CLIENT_CONF" ] && ! _wg_conf_has_mtu "$WG_CLIENT_CONF"; then
        _wg_insert_interface_line "$WG_CLIENT_CONF" "MTU = $WG_MTU" \
            && log "migrated: MTU = $WG_MTU -> $WG_CLIENT_CONF"
    fi

    if grep -q '^[[:space:]]*PostUp[[:space:]]*=' "$WG_CONF" && ! grep -q 'TCPMSS' "$WG_CONF"; then
        local tmp="$WG_CONF.mig"
        # awk в условии if: под set -e его падение иначе убило бы весь control.sh.
        if awk -v u="; $(_wg_mss_rule -A '%i')" -v d="; $(_wg_mss_rule -D '%i')" '
            /^[[:space:]]*PostUp[[:space:]]*=/   { print $0 u; next }
            /^[[:space:]]*PostDown[[:space:]]*=/ { print $0 d; next }
            { print }' "$WG_CONF" > "$tmp" 2>/dev/null && [ -s "$tmp" ]; then
            chmod 600 "$tmp"; mv -f "$tmp" "$WG_CONF"
            log "migrated: MSS clamp -> $WG_CONF"
        else
            rm -f "$tmp"
        fi
    fi

    _wg_mtu_apply_live
}

# Донести MTU/clamp до поднятого интерфейса: wg syncconf их не применяет.
_wg_mtu_apply_live() {
    ip link show "$WG_IFACE" >/dev/null 2>&1 || return 0
    ip link set dev "$WG_IFACE" mtu "$WG_MTU" >/dev/null 2>&1 || true
    command -v iptables >/dev/null 2>&1 || return 0
    # -D перед -A вместо -C: правил два, проверять цепочку дороже, чем снять и вернуть.
    eval "$(_wg_mss_rule -D "$WG_IFACE")" >/dev/null 2>&1 || true
    eval "$(_wg_mss_rule -A "$WG_IFACE")" >/dev/null 2>&1 || true
}

# Идемпотентный бутстрап ft-wg0. Требует root.
wg_ensure() {
    _wg_tools_ensure
    mkdir -p "$WG_DIR"
    _wg_migrate_legacy
    if wg_present; then
        _wg_migrate_mtu
        _wg_up || fail wg_up_failed "wg-quick up $WG_IFACE failed; see logs"
        _wg_ensure_owner_client
    else
        _wg_port_free_check
        _wg_create_fresh
        _wg_up || fail wg_up_failed "wg-quick up $WG_IFACE failed; see logs"
    fi
}

# base64 клиентского conf в data-поле (или лог, если base64 нет).
_emit_client_conf() {
    [ -f "$WG_CLIENT_CONF" ] || return 0
    if ! command -v base64 >/dev/null 2>&1; then
        log "base64 unavailable - import client conf manually: $WG_CLIENT_CONF"
        return 0
    fi
    d_str client_conf_b64 "$(base64 < "$WG_CLIENT_CONF" | tr -d '\n')"
}

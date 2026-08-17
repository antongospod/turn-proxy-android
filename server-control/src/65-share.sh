# Имя пира - base64-маркер "# ft-user: <b64>" в блоке [Peer]. Conf пира лежит в
# $SHARE_DIR для повторной выдачи; cid <-> pub связывается файлом <pub_fs>.cid.

_pub_fs() {  # wg-pubkey -> имя файла ('/'+'=' недопустимы в путях)
    printf '%s' "$1" | tr '/+' '_-' | tr -d '='
}

# pubkey пира владельца (из wireguard-client.conf). Пусто, если conf нет.
_self_pub() {
    [ -f "$WG_CLIENT_CONF" ] || return 0
    local cp
    cp=$(sed -n 's/^[[:space:]]*PrivateKey[[:space:]]*=[[:space:]]*//p' "$WG_CLIENT_CONF" | head -n1 | tr -d ' \r')
    [ -n "$cp" ] || return 0
    printf '%s' "$cp" | wg pubkey 2>/dev/null || true
}

_name_from_b64() { printf '%s' "$1" | base64 -d 2>/dev/null || true; }

# 0, если блок [Peer] с этим PublicKey несёт маркер "# ft-user:" (выдан приложением).
_peer_marked() {  # CONF PUBKEY
    awk -v key="$2" '
        function check() { if (hit && marked) ok = 1 }
        /^[ \t]*\[/ { check(); hit = 0; marked = 0; next }
        /^[ \t]*# ft-user: / { marked = 1 }
        { line = $0; gsub(/[ \t\r]/, "", line); if (line == "PublicKey=" key) hit = 1 }
        END { check(); exit ok ? 0 : 1 }
    ' "$1"
}

# Один элемент массива peers. Видит локали peers_json (динамический scope bash).
_peers_flush() {
    if [ "$in_peer" = 1 ] && [ -n "$pub" ]; then
        local h conf_yes el
        h=$(printf '%s\n' "$hs" | awk -v p="$pub" '$1==p{print $2}')
        if [ -f "$SHARE_DIR/$(_pub_fs "$pub").conf" ]; then conf_yes=true; else conf_yes=false; fi
        el="{\"pub\":\"$(esc "$pub")\""
        if [ -n "$name" ]; then el="$el,\"name_b64\":\"$(esc "$name")\""; fi
        if [ -n "$ip" ]; then el="$el,\"ip\":\"$(esc "$ip")\""; fi
        if [ -n "$h" ] && [ "$h" != 0 ]; then el="$el,\"hs\":$h"; fi
        el="$el,\"has_conf\":$conf_yes}"
        if [ "$first" = 1 ]; then first=0; else _PEERS_OUT="$_PEERS_OUT,"; fi
        _PEERS_OUT="$_PEERS_OUT$el"
    fi
    pub=""; name=""; ip=""
}

# JSON-массив пиров из НАШЕГО conf. Чистка строк через parameter expansion
# (без форк-пайпов tr|sed на каждую строку - сотни форков на больших списках).
peers_json() {
    if ! wg_present || ! wg_is_ours; then printf '[]'; return 0; fi
    local hs="" _PEERS_OUT="" first=1 in_peer=0 pub="" name="" ip="" raw line val
    if command -v wg >/dev/null 2>&1; then
        hs=$(wg show "$WG_IFACE" latest-handshakes 2>/dev/null || true)
    fi
    while IFS= read -r raw || [ -n "$raw" ]; do
        line=${raw//$'\r'/}
        line=${line#"${line%%[![:space:]]*}"}
        line=${line%"${line##*[![:space:]]}"}
        case "$line" in
            "["*)
                _peers_flush
                case "$line" in \[[Pp]eer\]) in_peer=1 ;; *) in_peer=0 ;; esac
                ;;
            "# ft-user: "*)
                if [ "$in_peer" = 1 ]; then name="${line#\# ft-user: }"; fi ;;
            PublicKey*=*)
                if [ "$in_peer" = 1 ]; then val=${line#*=}; pub=${val// /}; fi ;;
            AllowedIPs*=*)
                if [ "$in_peer" = 1 ] && [ -z "$ip" ]; then
                    val=${line#*=}; val=${val%%,*}; val=${val%%/*}; ip=${val// /}
                fi ;;
        esac
    done < "$WG_CONF"
    _peers_flush
    printf '[%s]' "$_PEERS_OUT"
}

# Вырезает блок [Peer] с данным PublicKey целиком (заголовок + строки до
# следующей секции, включая маркер-комментарий). Возвращает в tmp.
_cut_peer_block() {  # CONF PUBKEY OUT_TMP
    awk -v key="$2" '
        function flushbuf() { for (j = 0; j < n; j++) print buf[j]; n = 0 }
        /^[ \t]*\[/ {
            if (insec) { if (drop) n = 0; flushbuf() }
            insec = 1; drop = 0; buf[n++] = $0; next
        }
        {
            if (!insec) { print; next }
            buf[n++] = $0
            line = $0; gsub(/[ \t\r]/, "", line)
            if (line == "PublicKey=" key) drop = 1
        }
        END { if (insec) { if (drop) n = 0; flushbuf() } }
    ' "$1" > "$3"
}

# Есть ли пир с таким PublicKey в conf.
_peer_in_conf() {  # CONF PUBKEY
    awk -v key="$2" '
        { line=$0; gsub(/[ \t\r]/,"",line); if (line=="PublicKey="key) found=1 }
        END { exit found?0:1 }
    ' "$1"
}

# obf_profile отдаётся всегда, когда параметры запуска нашлись (пусть и "none"):
# по нему клиент отличает "сервер стартовал из приложения" от "данных нет".
share_info_emit() {
    local backend=false obf="" key=""
    if wg_present && wg_is_ours; then backend=true; fi
    if [ -f "$ARGSFILE" ]; then
        obf=$(awk 'prev=="-obf-profile"{print;exit}{prev=$0}' "$ARGSFILE"); obf=${obf:-none}
        key=$(awk 'prev=="-obf-key"{print;exit}{prev=$0}' "$ARGSFILE")
    else
        local cmdline
        cmdline=$(current_cmdline)
        if [ -n "$cmdline" ]; then
            obf=$(printf '%s' "$cmdline" | sed -nE 's/.*-obf-profile[= ]+([a-z0-9]+).*/\1/p'); obf=${obf:-none}
            key=$(printf '%s' "$cmdline" | sed -nE 's/.*-obf-key[= ]+([0-9a-fA-F]{64}).*/\1/p')
        fi
    fi
    d_bool wg_backend "$backend"
    if [ -n "$obf" ]; then d_str obf_profile "$obf"; fi
    if [ -n "$key" ]; then d_str obf_key "$key"; fi
    return 0
}

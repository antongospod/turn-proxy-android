#!/usr/bin/env bats
# White-box: перечисление пиров/гостей, вырез блока, share-info. Sandbox FT_*.
# Не требуют root/wg (wg отсутствует - hs пустые, парс conf работает).

setup() {
    SRC="$BATS_TEST_DIRNAME/../src"
    LIB="$BATS_TEST_TMPDIR/lib.sh"
    : > "$LIB"
    for f in $(ls "$SRC"/*.sh | sort | grep -v '99-main'); do
        cat "$f" >> "$LIB"; printf '\n' >> "$LIB"
    done
    export FT_PREFIX="$BATS_TEST_TMPDIR/prefix"
    export FT_WG_DIR="$BATS_TEST_TMPDIR/wg"
    export FT_WG_IFACE="ft-wg0"
    mkdir -p "$FT_PREFIX/share" "$FT_WG_DIR"
    # shellcheck disable=SC1090
    source "$LIB"
    set +e; trap - EXIT
    CONF="$FT_WG_DIR/ft-wg0.conf"
}

_two_peers() {
    cat > "$CONF" <<EOF
[Interface]
$WG_MARKER
Address = 10.13.13.1/24
ListenPort = 51820

[Peer]
# owner
PublicKey = OWNERkeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
AllowedIPs = 10.13.13.2/32

[Peer]
# ft-user: $(printf 'Папа' | base64)
PublicKey = GUESTkeyBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
AllowedIPs = 10.13.13.3/32
EOF
}

@test "_pub_fs: '/' '+' '=' заменяются/срезаются" {
    [ "$(_pub_fs 'ab/cd+ef=')" = "ab_cd-ef" ]
}

@test "peers_json: нет conf -> []" {
    rm -f "$CONF"
    [ "$(peers_json)" = "[]" ]
}

@test "peers_json: два пира, маркеры и ip" {
    _two_peers
    out=$(peers_json)
    [[ "$out" == *'"pub":"OWNERkeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="'* ]]
    [[ "$out" == *'"pub":"GUESTkeyBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="'* ]]
    [[ "$out" == *'"ip":"10.13.13.3"'* ]]
    # имя гостя base64("Папа")
    [[ "$out" == *"\"name_b64\":\"$(printf 'Папа'|base64)\""* ]]
    [[ "$out" == *'"has_conf":false'* ]]
}

@test "peers_json: чужой conf (без маркера) -> []" {
    printf '[Interface]\nAddress=10.0.0.1/24\n[Peer]\nPublicKey=X=\n' > "$CONF"
    [ "$(peers_json)" = "[]" ]
}

@test "_cut_peer_block: вырезает целевой, сохраняет остальные" {
    _two_peers
    _cut_peer_block "$CONF" "GUESTkeyBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=" "$CONF.tmp"
    run grep -c 'PublicKey' "$CONF.tmp"
    [ "$output" = "1" ]
    grep -q 'OWNERkey' "$CONF.tmp"
    ! grep -q 'GUESTkey' "$CONF.tmp"
}

@test "_peer_in_conf: есть/нет" {
    _two_peers
    _peer_in_conf "$CONF" "OWNERkeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    run _peer_in_conf "$CONF" "NOSUCHkeyZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ="
    [ "$status" -ne 0 ]
}

@test "_peer_marked: ft-user -> 0, owner -> 1" {
    _two_peers
    _peer_marked "$CONF" "GUESTkeyBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    run _peer_marked "$CONF" "OWNERkeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    [ "$status" -ne 0 ]
}

@test "share_info_emit: из run.args (obf)" {
    printf -- '-listen\n:8443\n-obf-profile\nrtpopus\n-obf-key\ndeadbeef\n' > "$FT_PREFIX/run.args"
    _two_peers
    _DATA=()
    share_info_emit
    out=$(IFS=,; printf '%s' "${_DATA[*]}")
    [[ "$out" == *'"wg_backend":true'* ]]
    [[ "$out" == *'"obf_profile":"rtpopus"'* ]]
}

@test "share_info_emit: run.args без obf -> obf_profile none" {
    printf -- '-listen\n:8443\n-connect\n127.0.0.1:51820\n' > "$FT_PREFIX/run.args"
    _DATA=()
    share_info_emit
    out=$(IFS=,; printf '%s' "${_DATA[*]}")
    [[ "$out" == *'"obf_profile":"none"'* ]]
}

@test "clients_json: нет бинаря -> []" {
    [ "$(clients_json)" = "[]" ]
}

@test "_name_from_b64: декод" {
    [ "$(_name_from_b64 "$(printf 'Мама'|base64)")" = "Мама" ]
}

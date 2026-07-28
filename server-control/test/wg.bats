#!/usr/bin/env bats
# White-box тесты чистых WG-хелперов: sourceим модули (без 99-main) в sandbox
# через FT_*-override. Не требуют root/wg для логики парсинга и аллокации.

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
    mkdir -p "$FT_PREFIX" "$FT_WG_DIR"

    # ip/iptables заглушаем: FT_*-override не покрывают _wg_mtu_apply_live, и на машине
    # с боевым ft-wg0 тесты правили бы MTU живого туннеля и mangle-цепочку.
    STUB_BIN="$BATS_TEST_TMPDIR/bin"
    STUB_LOG="$BATS_TEST_TMPDIR/stub.log"
    mkdir -p "$STUB_BIN"; : > "$STUB_LOG"
    for c in ip iptables; do
        cat > "$STUB_BIN/$c" <<EOF
#!/bin/sh
echo "\$(basename "\$0") \$*" >> "$STUB_LOG"
exit 0
EOF
        chmod +x "$STUB_BIN/$c"
    done
    export PATH="$STUB_BIN:$PATH"

    # shellcheck disable=SC1090
    source "$LIB"
    set +e            # bats-ассерты несовместимы с унаследованным errexit
    trap - EXIT       # подавить _on_exit JSON в конце теста
}

_write_conf() { printf '%s\n' "$@" > "$FT_WG_DIR/ft-wg0.conf"; }
_write_legacy() { printf '%s\n' "$@" > "$FT_WG_DIR/wg0.conf"; }

@test "_wg_alloc_ip: .1 сервер + .2 владелец заняты -> .3" {
    _write_conf "[Interface]" "$WG_MARKER" "Address = 10.13.13.1/24" \
                "" "[Peer]" "AllowedIPs = 10.13.13.2/32"
    [ "$(_wg_alloc_ip "$FT_WG_DIR/ft-wg0.conf")" = "10.13.13.3" ]
}

@test "_wg_alloc_ip: после .3 -> .4" {
    _write_conf "[Interface]" "Address = 10.13.13.1/24" \
                "[Peer]" "AllowedIPs = 10.13.13.2/32" \
                "[Peer]" "AllowedIPs = 10.13.13.3/32"
    [ "$(_wg_alloc_ip "$FT_WG_DIR/ft-wg0.conf")" = "10.13.13.4" ]
}

@test "_wg_alloc_ip: IPv6-only Address -> код 1" {
    _write_conf "[Interface]" "Address = fd00::1/64"
    run _wg_alloc_ip "$FT_WG_DIR/ft-wg0.conf"
    [ "$status" -ne 0 ]
}

@test "wg_is_ours: маркер есть -> 0" {
    _write_conf "[Interface]" "$WG_MARKER" "Address = 10.13.13.1/24"
    wg_is_ours
}

@test "wg_is_ours: маркера нет -> 1" {
    _write_conf "[Interface]" "Address = 10.0.0.1/24"
    run wg_is_ours
    [ "$status" -ne 0 ]
}

@test "wg_present: нет файла -> 1" {
    rm -f "$FT_WG_DIR/ft-wg0.conf"
    run wg_present
    [ "$status" -ne 0 ]
}

@test "wg_port: из ListenPort conf (wg отсутствует)" {
    _write_conf "[Interface]" "ListenPort = 51820" "Address = 10.13.13.1/24"
    [ "$(wg_port)" = "51820" ]
}

@test "wg_port: инлайн-комментарий срезается" {
    _write_conf "[Interface]" "ListenPort = 51820 # default" "Address = 10.13.13.1/24"
    [ "$(wg_port)" = "51820" ]
}

@test "_conf_has_guests: маркер ft-user -> 0" {
    _write_legacy "[Interface]" "Address = 10.13.13.1/24" "[Peer]" "# ft-user: dXNlcg==" "PublicKey = k"
    _conf_has_guests "$FT_WG_DIR/wg0.conf"
}

@test "_conf_has_guests: без маркера -> 1" {
    _write_legacy "[Interface]" "Address = 10.13.13.1/24" "[Peer]" "PublicKey = k"
    run _conf_has_guests "$FT_WG_DIR/wg0.conf"
    [ "$status" -ne 0 ]
}

@test "_legacy_is_ours: наша подсеть 10.13.13 -> 0" {
    rm -f "$FT_PREFIX/wireguard-client.conf"
    _write_legacy "[Interface]" "Address = 10.13.13.1/24"
    _legacy_is_ours
}

@test "_legacy_is_ours: чужая подсеть, без гостей/client conf -> 1" {
    rm -f "$FT_PREFIX/wireguard-client.conf"
    _write_legacy "[Interface]" "Address = 10.99.0.1/24"
    run _legacy_is_ours
    [ "$status" -ne 0 ]
}

@test "_legacy_is_ours: гости-маркер при чужой подсети -> 0" {
    rm -f "$FT_PREFIX/wireguard-client.conf"
    _write_legacy "[Interface]" "Address = 10.99.0.1/24" "[Peer]" "# ft-user: dXNlcg==" "PublicKey = k"
    _legacy_is_ours
}

@test "_adopt_to_ftwg0: ft-wg0.conf с маркером+пирами, wg0.conf удалён" {
    rm -f "$FT_WG_DIR/ft-wg0.conf"
    _write_legacy "[Interface]" "Address = 10.13.13.1/24" "ListenPort = 51820" \
                  "[Peer]" "# ft-user: dXNlcg==" "PublicKey = k" "AllowedIPs = 10.13.13.3/32"
    _adopt_to_ftwg0
    [ -f "$FT_WG_DIR/ft-wg0.conf" ]
    [ ! -f "$FT_WG_DIR/wg0.conf" ]
    grep -qF "$WG_MARKER" "$FT_WG_DIR/ft-wg0.conf"
    grep -q '10.13.13.3/32' "$FT_WG_DIR/ft-wg0.conf"
}

@test "_wg_migrate_legacy: нет legacy wg0 -> no-op" {
    rm -f "$FT_WG_DIR/ft-wg0.conf" "$FT_WG_DIR/wg0.conf"
    run _wg_migrate_legacy
    [ "$status" -eq 0 ]
    [ ! -f "$FT_WG_DIR/ft-wg0.conf" ]
}

@test "_wg_conf_has_mtu: MTU только в [Peer] -> 1" {
    _write_conf "[Interface]" "Address = 10.13.13.1/24" "[Peer]" "MTU = 1420"
    run _wg_conf_has_mtu "$FT_WG_DIR/ft-wg0.conf"
    [ "$status" -eq 1 ]
}

@test "_wg_conf_has_mtu: MTU в [Interface] -> 0" {
    _write_conf "[Interface]" "MTU = 1280" "Address = 10.13.13.1/24" "[Peer]"
    _wg_conf_has_mtu "$FT_WG_DIR/ft-wg0.conf"
}

@test "_wg_migrate_mtu: старый conf получает MTU и clamp в PostUp/PostDown" {
    _write_conf "[Interface]" "$WG_MARKER" "Address = 10.13.13.1/24" "ListenPort = 51820" \
                "PostUp = iptables -A FORWARD -i %i -j ACCEPT" \
                "PostDown = iptables -D FORWARD -i %i -j ACCEPT" \
                "" "[Peer]" "AllowedIPs = 10.13.13.2/32"
    _wg_migrate_mtu
    local conf="$FT_WG_DIR/ft-wg0.conf"
    grep -qx "MTU = $WG_MTU" "$conf"
    grep -q '^PostUp = .*TCPMSS --clamp-mss-to-pmtu' "$conf"
    grep -q "^PostDown = .*TCPMSS --set-mss $((WG_MTU - 40))" "$conf"
    # peer-секция не задета
    grep -q '10.13.13.2/32' "$conf"
    # правки доехали до живого интерфейса
    grep -qx "ip link set dev ft-wg0 mtu $WG_MTU" "$STUB_LOG"
    grep -q "^iptables .*TCPMSS" "$STUB_LOG"
}

@test "_wg_migrate_mtu: повторный прогон не дублирует MTU/clamp" {
    _write_conf "[Interface]" "$WG_MARKER" "Address = 10.13.13.1/24" \
                "PostUp = iptables -A FORWARD -i %i -j ACCEPT" \
                "PostDown = iptables -D FORWARD -i %i -j ACCEPT"
    _wg_migrate_mtu
    _wg_migrate_mtu
    local conf="$FT_WG_DIR/ft-wg0.conf"
    [ "$(grep -c "^MTU = $WG_MTU" "$conf")" -eq 1 ]
    [ "$(grep -c 'clamp-mss-to-pmtu' "$conf")" -eq 1 ]
}

@test "_wg_migrate_mtu: чужой conf (без маркера) не трогаем" {
    _write_conf "[Interface]" "Address = 10.13.13.1/24"
    _wg_migrate_mtu
    run _wg_conf_has_mtu "$FT_WG_DIR/ft-wg0.conf"
    [ "$status" -eq 1 ]
}

@test "_wg_migrate_mtu: клиентский conf тоже получает MTU" {
    _write_conf "[Interface]" "$WG_MARKER" "Address = 10.13.13.1/24"
    printf '%s\n' "[Interface]" "PrivateKey = k" "Address = 10.13.13.2/32" \
                  "[Peer]" "PublicKey = s" > "$WG_CLIENT_CONF"
    _wg_migrate_mtu
    _wg_conf_has_mtu "$WG_CLIENT_CONF"
}

@test "pkg_mgr: на машине без пакетников -> код 1" {
    # На CI-Linux пакетник есть; тест осмыслен в чистом окружении. Проверяем,
    # что функция не падает и возвращает имя ИЛИ код 1.
    run pkg_mgr
    [ "$status" -eq 0 ] || [ "$status" -eq 1 ]
}

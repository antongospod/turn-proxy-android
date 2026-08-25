#!/usr/bin/env bats
# White-box: сборка аргументов запуска сервера (run.args). Sandbox FT_*.

setup() {
    SRC="$BATS_TEST_DIRNAME/../src"
    LIB="$BATS_TEST_TMPDIR/lib.sh"
    : > "$LIB"
    for f in $(ls "$SRC"/*.sh | sort | grep -v '99-main'); do
        cat "$f" >> "$LIB"; printf '\n' >> "$LIB"
    done
    export FT_PREFIX="$BATS_TEST_TMPDIR/prefix"
    mkdir -p "$FT_PREFIX"
    # shellcheck disable=SC1090
    source "$LIB"
    set +e; trap - EXIT
    ARG_LISTEN="0.0.0.0:56000"
    ARG_CONNECT="127.0.0.1:51820"
}

@test "run.args: udp-режим не пишет -mode и -kcp-*" {
    _write_args_file
    run cat "$ARGSFILE"
    [[ "$output" != *"-mode"* ]]
    [[ "$output" != *"-kcp-"* ]]
}

@test "run.args: tcp-режим с профилем ARQ" {
    ARG_MODE="tcp"
    ARG_KCP=(-kcp-interval 40 -kcp-sndwnd 256 -kcp-acknodelay=false)
    _write_args_file
    run cat "$ARGSFILE"
    [[ "$output" == *$'-mode\ntcp'* ]]
    [[ "$output" == *$'-kcp-interval\n40'* ]]
    [[ "$output" == *$'-kcp-sndwnd\n256'* ]]
    [[ "$output" == *'-kcp-acknodelay=false'* ]]
}

@test "parse_args: --kcp-interval -> argv-пара" {
    parse_args --listen=0.0.0.0:56000 --connect=127.0.0.1:443 --mode=tcp --kcp-interval=40
    [ "$ARG_MODE" = "tcp" ]
    [ "${ARG_KCP[0]}" = "-kcp-interval" ]
    [ "${ARG_KCP[1]}" = "40" ]
}

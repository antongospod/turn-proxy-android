#!/usr/bin/env bats
# Black-box тесты собранного control-скрипта. Запуск: bats server-control/test/
# (нужен bats + bash). Скрипт root-предполагающий, но probe/диспетчер работают
# и под обычным юзером - проверяем именно контракт вывода (JSON v2).

setup() {
    SRC="$BATS_TEST_DIRNAME/../src"
    SCRIPT="$BATS_TEST_TMPDIR/free-turn-control.sh"
    : > "$SCRIPT"
    for f in $(ls "$SRC"/*.sh | sort); do
        cat "$f" >> "$SCRIPT"
        printf '\n' >> "$SCRIPT"
    done
    # Изолированный PATH-стаб для имитации конфликтующих менеджеров.
    STUB="$BATS_TEST_TMPDIR/bin"
    mkdir -p "$STUB"
}

run_script() { run bash "$SCRIPT" "$@"; }

@test "assembled script passes bash -n" {
    run bash -n "$SCRIPT"
    [ "$status" -eq 0 ]
}

@test "probe -> result ok, proto 2" {
    run_script probe
    [ "$status" -eq 0 ]
    [[ "$output" == *'"proto":2'* ]]
    [[ "$output" == *'"result":"ok"'* ]]
}

@test "probe выдаёт ровно одну строку JSON" {
    run_script probe
    [ "${#lines[@]}" -eq 1 ]
}

@test "probe содержит обязательные поля контракта" {
    run_script probe
    [[ "$output" == *'"installed":'* ]]
    [[ "$output" == *'"running":'* ]]
    [[ "$output" == *'"runtime":'* ]]
    [[ "$output" == *'"euid":'* ]]
    [[ "$output" == *'"wg":{'* ]]
    [[ "$output" == *'"conflicts":{'* ]]
    [[ "$output" == *'"virt":'* ]]
}

@test "неизвестная сабкоманда -> err bad_arg, exit 1" {
    run_script frobnicate
    [ "$status" -eq 1 ]
    [[ "$output" == *'"result":"err"'* ]]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "неизвестный аргумент -> err bad_arg" {
    run_script logs --bogus=1
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "пустой вызов -> err bad_arg (no subcommand)" {
    run_script
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "невалидный --port -> err bad_arg" {
    run_script wg-setup --port=abc --endpoint=1.2.3.4:51820
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "невалидный --mode -> err bad_arg" {
    run_script start --listen=0.0.0.0:56000 --connect=127.0.0.1:443 --mode=quic
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "нечисловой --kcp-interval -> err bad_arg" {
    run_script start --listen=0.0.0.0:56000 --connect=127.0.0.1:443 --kcp-interval=fast
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "IPv6-endpoint принимается парсером (не bad_arg)" {
    # Под обычным юзером упрётся в needs_root - но parse_args уже принял [v6]:port.
    run_script wg-setup --port=51820 "--endpoint=[2001:db8::1]:51820"
    [[ "$output" != *'"code":"bad_arg"'* ]]
}

@test "conflict_warp ловит warp-cli из PATH" {
    printf '#!/bin/sh\nexit 0\n' > "$STUB/warp-cli"
    chmod +x "$STUB/warp-cli"
    PATH="$STUB:$PATH" run bash "$SCRIPT" probe
    [[ "$output" == *'"warp":true'* ]]
}

@test "stop -> ok stopped (rt_stop best-effort без сервера)" {
    run_script stop
    [[ "$output" == *'"result":"ok"'* ]]
    [[ "$output" == *'"stopped":true'* ]]
}

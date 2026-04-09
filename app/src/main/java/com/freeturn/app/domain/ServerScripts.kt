package com.freeturn.app.domain

object ServerScripts {

    /** Удаляет из строки всё, кроме допустимых символов host:port (защита от shell-инъекций) */
    private fun shellSafe(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._:\\-]"), "")

    val checkServerState: String = """
        if ls /opt/vk-turn/server-linux-* >/dev/null 2>&1; then echo "INSTALLED:YES"; else echo "INSTALLED:NO"; fi
        if ps aux | grep -v grep | grep -q "server-linux-"; then
            echo "RUNNING:YES"
            if ps aux | grep -v grep | grep "server-linux-" | grep -q "\-vless"; then echo "VLESS:YES"; else echo "VLESS:NO"; fi
        else
            echo "RUNNING:NO"
        fi
    """.trimIndent()

    val installServer: String = """
        mkdir -p /opt/vk-turn && cd /opt/vk-turn
        if [ -f /opt/vk-turn/proxy.pid ]; then kill -9 ${'$'}(cat /opt/vk-turn/proxy.pid) 2>/dev/null; rm -f /opt/vk-turn/proxy.pid; fi
        ARCH=${'$'}(uname -m)
        if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi
        BASE_URL="https://github.com/cacggghp/vk-turn-proxy/releases/latest/download"
        echo "Arch: ${'$'}ARCH | Binary: ${'$'}BIN"
        _dl() { URL=${'$'}1; OUT=${'$'}2
            if command -v curl >/dev/null 2>&1; then
                curl -sSL -o "${'$'}OUT" "${'$'}URL" 2>&1
            elif command -v wget >/dev/null 2>&1; then
                wget -q -O "${'$'}OUT" "${'$'}URL" 2>&1
            else
                echo "ERROR: curl и wget не найдены"; return 1
            fi
        }
        _dl "${'$'}BASE_URL/${'$'}BIN" "${'$'}BIN" || { echo "ERROR: скачивание бинарника не удалось"; exit 1; }
        SIZE=${'$'}(wc -c < "${'$'}BIN" 2>/dev/null || echo 0)
        echo "Size: ${'$'}SIZE bytes"
        if [ "${'$'}SIZE" -lt 100000 ]; then echo "ERROR: файл слишком мал (${'$'}SIZE байт)"; cat "${'$'}BIN" 2>/dev/null; exit 1; fi
        if _dl "${'$'}BASE_URL/checksums.txt" checksums.txt && [ -s checksums.txt ]; then
            EXPECTED=${'$'}(grep "${'$'}BIN" checksums.txt | awk '{print ${'$'}1}')
            if [ -n "${'$'}EXPECTED" ]; then
                ACTUAL=${'$'}(sha256sum "${'$'}BIN" | awk '{print ${'$'}1}')
                if [ "${'$'}EXPECTED" = "${'$'}ACTUAL" ]; then
                    echo "SHA256: OK"
                else
                    echo "ERROR: SHA256 не совпадает — ожидался ${'$'}EXPECTED, получен ${'$'}ACTUAL"
                    rm -f "${'$'}BIN" checksums.txt; exit 1
                fi
            else
                echo "WARN: ${'$'}BIN не найден в checksums.txt, SHA256 пропущен"
            fi
            rm -f checksums.txt
        else
            echo "WARN: checksums.txt недоступен, SHA256 не проверен"
        fi
        chmod +x "${'$'}BIN" && echo "DONE"
    """.trimIndent()

    fun startServer(listen: String, connect: String, vlessMode: Boolean = false): String {
        val safeListen = shellSafe(listen)
        val safeConnect = shellSafe(connect)
        val vlessFlag = if (vlessMode) " -vless" else ""
        return """
            cd /opt/vk-turn &&
            ARCH=${'$'}(uname -m);
            if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi;
            nohup ./${'$'}BIN -listen '${safeListen}' -connect '${safeConnect}'${vlessFlag} > server.log 2>&1 &
            echo ${'$'}! > proxy.pid && echo "STARTED"
        """.trimIndent()
    }

    val stopServer: String = """
        cd /opt/vk-turn &&
        if [ -f proxy.pid ]; then kill -9 ${'$'}(cat proxy.pid) 2>/dev/null; rm -f proxy.pid; fi;
        pkill -9 -f "[s]erver-linux-" 2>/dev/null;
        echo "STOPPED"
    """.trimIndent()

    val fetchServerLogs: String = "tail -n 80 /opt/vk-turn/server.log 2>/dev/null || echo '(лог пуст)'"
}

package com.freeturn.app.domain.server

sealed class ServerCommand {
    data object Probe : ServerCommand()
    data object Install : ServerCommand()

    data class WgSetup(
        val port: Int,
        val endpoint: String
    ) : ServerCommand()
    data class Start(val opts: ServerStartOptions) : ServerCommand()
    data object Stop : ServerCommand()
    data class FetchLogs(val lines: Int = 80) : ServerCommand()

    data class Uninstall(
        val withWgPkg: Boolean = false,
        val dryRun: Boolean = false
    ) : ServerCommand()

    data object ShareInfo : ServerCommand()

    data class PeerAdd(
        val nameB64: String,
        val endpoint: String,
        val clientId: String
    ) : ServerCommand()

    data object ShareList : ServerCommand()

    data class PeerConf(
        val pubkey: String,
        val clientId: String,
        val nameB64: String
    ) : ServerCommand()
    data class PeerRemove(val pubkey: String) : ServerCommand()

    data class ClientAdd(val nameB64: String, val clientId: String) : ServerCommand()
    data class ClientRemove(val clientId: String) : ServerCommand()

    fun toArgv(): List<String> = when (this) {
        is Probe -> listOf("probe")
        is Install -> listOf("install")
        is WgSetup -> buildList {
            add("wg-setup")
            add("--port=$port")
            add("--endpoint=$endpoint")
        }
        is Start -> buildList {
            add("start")
            add("--listen=${opts.listen}")
            add("--connect=${opts.connect}")
            if (opts.obfProfile != "none" && opts.obfKey.isNotBlank()) {
                add("--obf-profile=${opts.obfProfile}")
                add("--obf-key=${opts.obfKey}")
            }
            if (opts.clientId.isNotBlank()) add("--client-id=${opts.clientId}")
        }
        is Stop -> listOf("stop")
        is FetchLogs -> listOf("logs", "--tail=$lines")
        is ShareInfo -> listOf("share-info")
        is PeerAdd -> buildList {
            add("peer-add")
            add("--name-b64=$nameB64")
            add("--endpoint=$endpoint")
            if (clientId.isNotBlank()) add("--client-id=$clientId")
        }
        is ShareList -> listOf("share-list")
        is PeerConf -> buildList {
            add("peer-conf")
            add("--pubkey=$pubkey")
            if (clientId.isNotBlank()) add("--client-id=$clientId")
            if (nameB64.isNotBlank()) add("--name-b64=$nameB64")
        }
        is PeerRemove -> listOf("peer-remove", "--pubkey=$pubkey")
        is ClientAdd -> listOf("client-add", "--name-b64=$nameB64", "--client-id=$clientId")
        is ClientRemove -> listOf("client-remove", "--client-id=$clientId")
        is Uninstall -> buildList {
            add("uninstall")
            if (withWgPkg) add("--with-wg-pkg")
            if (dryRun) add("--dry-run")
        }
    }
}

data class ServerStartOptions(
    val listen: String,
    val connect: String,
    val obfProfile: String = "none",
    val obfKey: String = "",
    val clientId: String = ""
)

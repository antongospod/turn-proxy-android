#!/usr/bin/env bash
# СГЕНЕРИРОВАН из server-control/src/*.sh (Gradle assembleControlScript) - правки в src/.
# Стримится по SSH stdin (bash -s -- <subcmd> <args>), вывод - один JSON-объект.
# Root-предполагающий; не-root эскалируется транспортом (sudo).

set -euo pipefail
umask 077

PROTO_VERSION=2

# FT_*-env переопределяют пути для bats-песочницы; в проде всегда дефолты.
PREFIX="${FT_PREFIX:-/opt/free-turn-proxy}"
STATE_FILE="$PREFIX/state"
PIDFILE="$PREFIX/proxy.pid"
LOCKFILE="$PREFIX/control.lock"
PEERS_LOCK="$PREFIX/peers.lock"
LOGFILE="$PREFIX/server.log"
VERFILE="$PREFIX/version"
ARGSFILE="$PREFIX/run.args"
ENVFILE="$PREFIX/run.env"
OBFFILE="$PREFIX/obf.key"
LAUNCHER="$PREFIX/launch.sh"
CLIENTSFILE="$PREFIX/clients.json"
OWNERCIDFILE="$PREFIX/owner.cid"
SHARE_DIR="$PREFIX/share"

UNIT_NAME="free-turn-proxy.service"
UNIT_PATH="/etc/systemd/system/$UNIT_NAME"
LEGACY_UNIT="vk-turn-proxy.service"

# Владеем ТОЛЬКО ft-wg0; маркер владения - в [Interface] conf. Чужой WG не трогаем.
WG_DIR="${FT_WG_DIR:-/etc/wireguard}"
WG_IFACE="${FT_WG_IFACE:-ft-wg0}"
WG_CONF="$WG_DIR/$WG_IFACE.conf"
WG_MARKER="# managed-by: free-turn-proxy"
WG_NET="${FT_WG_NET:-10.13.13}"
WG_CLIENT_CONF="$PREFIX/wireguard-client.conf"
# Туннель идёт поверх TURN (STUN-обёртка + UDP + IP): дефолтные 1420 не пролезают,
# сервер->клиент рвётся фрагментацией. 1280 - минимум IPv6, живёт на любом пути.
WG_MTU="${FT_WG_MTU:-1280}"

RELEASES_URL="https://github.com/samosvalishe/free-turn-proxy/releases"
BASE_URL="$RELEASES_URL/latest/download"

# secure_path в sudo срезает /usr/local/* (userspace-WG, ss).
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"
export PATH

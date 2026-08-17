#!/usr/bin/env bash
# =============================================================================
# CF优选IP 命令行版 (VPS/bash)
# 功能对齐 Android 版 v1.0.23:
#   随机选段(每段随机最后一位) → 多轮补齐到100个 → 并发RTT(20/批) → 期望时延筛选
#   → 并发联通性测试(20/批) → 前N单线程测速(每IP最长5s,达标即停) → 输出排序结果
#
#   --random = 测速候选不按 RTT 排序,从 RTT 有响应的 IP 随机取 N 个(默认关)
#
# 依赖: bash4+, curl, awk, grep, shuf(或 sort -R), date, head/tail/cut
# 用法:
#   ./cf-ip-picker.sh [--speed 10] [--latency 100] [--prefix 172.64.x.x] \
#                     [--count 100] [--top 10] [--batch 20] [--max-batches 20] \
#                     [--random] [--baidu] [--no-connectivity] [--verbose] [--help]
#
# 示例:
#   ./cf-ip-picker.sh                              # 默认扫100个,期望1Mbps
#   ./cf-ip-picker.sh --speed 20 --latency 150     # 期望20Mbps且延迟<=150ms
#   ./cf-ip-picker.sh --prefix 172.64.145.210      # 只测指定IP
#   ./cf-ip-picker.sh --prefix 172.64.x.x --count 200 --random   # 随机选测速候选
# =============================================================================
set -uo pipefail

# ------------------------------ 配置与默认值 ------------------------------
API_BASE="https://xiaoyahelper.zngle.cf"
CACHE_TTL=21600                      # 数据缓存 6 小时(秒)
RTT_TIMEOUT=3                        # RTT TCP 连接超时(秒)
SPEED_TIMEOUT=5                      # 单 IP 测速时长上限(秒)
EXPECTED_SPEED=1                     # 期望网速 Mbps(留空默认1,对齐 app)
EXPECTED_LATENCY=0                   # 期望时延 ms,0=不限
PREFIX=""                            # IP 前缀过滤
COUNT=100                            # 每批候选数
TOP=10                               # 测速个数(前 N 个)
BATCH=20                             # 并发批大小
MAX_BATCHES=20                       # 最多批次(防死循环)
BAIDU_PROXY=0                        # 是否走百度前置代理
DO_CONNECTIVITY=1                    # 是否做联通性测试
RANDOM_SELECT=0                      # 测速候选是否随机选(默认关=按RTT排序取前N;开=响应IP随机取N)
VERBOSE=0
BAIDU_PROXY_HOST="cloudnproxy.baidu.com"
BAIDU_PROXY_PORT=443
BAIDU_AUTH="X-T5-Auth: 482857715"

# ------------------------------ 参数解析 ------------------------------
usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --speed)          EXPECTED_SPEED="$2"; shift 2 ;;
        --latency)        EXPECTED_LATENCY="$2"; shift 2 ;;
        --prefix)         PREFIX="$2"; shift 2 ;;
        --count)          COUNT="$2"; shift 2 ;;
        --top)            TOP="$2"; shift 2 ;;
        --batch)          BATCH="$2"; shift 2 ;;
        --max-batches)    MAX_BATCHES="$2"; shift 2 ;;
        --random)         RANDOM_SELECT=1; shift ;;
        --baidu)          BAIDU_PROXY=1; shift ;;
        --no-connectivity) DO_CONNECTIVITY=0; shift ;;
        --verbose)        VERBOSE=1; shift ;;
        --help|-h)        usage ;;
        *) echo "未知参数: $1 (-h 查看帮助)"; exit 1 ;;
    esac
done

# ------------------------------ 辅助函数 ------------------------------
log()  { [[ $VERBOSE -eq 1 ]] && printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { echo "错误: $*" >&2; exit 1; }
msnow(){ date +%s%3N; }

# 带缓存拉取: $1=路径  $2=缓存key
api_get() {
    local path="$1"
    local key="$2"
    local cache="/tmp/cfip_${key}.cache"
    if [[ -f "$cache" ]] && [[ $(( $(date +%s) - $(stat -c %Y "$cache") )) -lt $CACHE_TTL ]]; then
        cat "$cache"
    else
        local data
        data=$(curl -sS -m 20 -A "CFIP-Picker/CLI/1.0" "$API_BASE/$path") || data=""
        [[ -n "$data" ]] && { echo "$data" > "$cache"; echo "$data"; }
    fi
}

# IP 段 → 随机末位 IP。$1=CIDR(如 172.64.145.0/24) → 172.64.145.N
random_ip_from_cidr() {
    local cidr="$1" base="${1%/*}" last
    last=$(( RANDOM % 256 ))
    echo "${base%.*}.$last"
}

# 前缀过滤: $1=CIDR, $2=前缀(如 172.64.x.x / 172.64.145.210)
cidr_matches_prefix() {
    local cidr="$1" prefix="$2"
    local base="${cidr%/*}"  # 172.64.145.0
    local p1 p2 p3 p4 b1 b2 b3 b4
    IFS='.' read -r p1 p2 p3 p4 <<< "$(echo "$prefix" | tr 'a-z' 'A-Z' | sed 's/X/*/g')"
    IFS='.' read -r b1 b2 b3 b4 <<< "$base"
    [[ ${p1:-*} != '*' ]] && [[ "$p1" != "$b1" ]] && return 1
    [[ ${p2:-*} != '*' ]] && [[ "$p2" != "$b2" ]] && return 1
    [[ ${p3:-*} != '*' ]] && [[ "$p3" != "$b3" ]] && return 1
    # 第四段是精确值时不在此判断(由 random_ip_from_cidr 返回固定值),匹配前3段即可
    return 0
}

# 前缀过滤下随机生成 IP: $1=CIDR, $2=前缀; 返回 IP 或空
filtered_random_ip() {
    local cidr="$1" prefix="$2"
    cidr_matches_prefix "$cidr" "$prefix" || return 1
    local base="${cidr%/*}" p4
    # 前缀第4段若为数字则固定
    p4=$(echo "$prefix" | awk -F. '{print $4}' | sed 's/X/*/g')
    if [[ "$p4" =~ ^[0-9]+$ ]]; then
        echo "${base%.*}.$p4"
    else
        random_ip_from_cidr "$cidr"
    fi
}

# 生成候选池(LinedHashSet 去重 + 多轮补齐,对齐 app)
generate_pool() {
    local -n _out="$1"; _out=()
    local ip
    local round=0
    # 前缀过滤时预先筛出匹配段,避免每轮全量遍历 6534 段(原先单段前缀慢到像死循环)
    local ranges="$ALL_RANGES"
    if [[ -n "$PREFIX" ]]; then
        local -a matched=()
        while IFS= read -r cidr; do
            [[ -z "$cidr" ]] && continue
            cidr_matches_prefix "$cidr" "$PREFIX" && matched+=("$cidr")
        done <<< "$ALL_RANGES"
        log "前缀匹配段: ${#matched[@]} 个"
        [[ ${#matched[@]} -eq 0 ]] && return
        ranges=$(printf '%s\n' "${matched[@]}")
    fi
    while [[ ${#_out[@]} -lt $COUNT && $round -lt 100 ]]; do
        round=$((round+1))
        # 每轮打乱段顺序,对齐 app v1.0.21 随机选段(避免总选数据源前面的段)
        local shuffled_ranges
        shuffled_ranges=$(echo "$ranges" | sort -R)
        while IFS= read -r cidr; do
            [[ -z "$cidr" ]] && continue
            [[ ${#_out[@]} -ge $COUNT ]] && break
            if [[ -n "$PREFIX" ]]; then
                ip=$(filtered_random_ip "$cidr" "$PREFIX" 2>/dev/null) || continue
            else
                ip=$(random_ip_from_cidr "$cidr")
            fi
            # 去重
            local dup=0
            for x in "${_out[@]}"; do [[ "$x" == "$ip" ]] && { dup=1; break; }; done
            [[ $dup -eq 0 ]] && _out+=("$ip")
        done <<< "$shuffled_ranges"
        log "第${round}轮补齐, 当前 ${#_out[@]}/${COUNT}"
    done
}

# RTT 测试单个 IP:/dev/tcp TCP 连接耗时(ms),失败返回空
rtt_one() {
    local ip="$1"
    local t0 t1 ms
    t0=$(date +%s%N)
    if timeout $RTT_TIMEOUT bash -c "exec 3<>/dev/tcp/$ip/443" 2>/dev/null; then
        t1=$(date +%s%N)
        ms=$(( (t1 - t0) / 1000000 ))
        echo "$ms"
    else
        echo ""
    fi
}

# 联通性测试: $1=IP; 成功用 http_test_url 请求返回非空码
conn_one() {
    local ip="$1"
    local host port path
    host=$(echo "$HTTP_TEST_URL" | sed -E 's#^https?://([^/]+)/.*#\1#')
    path=$(echo "$HTTP_TEST_URL" | sed -E 's#^https?://[^/]+(/.*)?$#\1#')
    [[ -z "$path" ]] && path="/"
    port=443
    if [[ $BAIDU_PROXY -eq 1 ]]; then
        curl -s -o /dev/null -w '%{http_code}' -m $((RTT_TIMEOUT+2)) \
            --proxy "https://$BAIDU_PROXY_HOST:$BAIDU_PROXY_PORT" \
            --proxy-header "$BAIDU_AUTH" \
            --connect-to "$host:$port:$ip:$port" \
            --resolve "$host:$port:$ip" \
            -H "Host: $host" -A "CFIP-Picker/CLI/1.0" \
            "https://$host$path" 2>/dev/null
    else
        curl -s -o /dev/null -w '%{http_code}' -m $((RTT_TIMEOUT+2)) \
            --resolve "$host:$port:$ip" \
            -H "Host: $host" -A "CFIP-Picker/CLI/1.0" \
            "https://$host$path" 2>/dev/null
    fi
}

# 测速单个 IP:返回 "带宽Mbps cf-ray"
speed_one() {
    local ip="$1"
    local host path port line cf_ray colo download_speed mbps
    host=$(echo "$SPEED_URL" | sed -E 's#^https?://([^/]+)/.*#\1#')
    path=$(echo "$SPEED_URL" | sed -E 's#^https?://[^/]+(/.*)?$#\1#')
    [[ -z "$path" ]] && path="/"
    port=443
    if [[ $BAIDU_PROXY -eq 1 ]]; then
        read -r download_speed cf_ray < <(curl -s -o /dev/null \
            -w '%{speed_download} %{header_json}' \
            --max-time $SPEED_TIMEOUT \
            --proxy "https://$BAIDU_PROXY_HOST:$BAIDU_PROXY_PORT" \
            --proxy-header "$BAIDU_AUTH" \
            --connect-to "$host:$port:$ip:$port" \
            --resolve "$host:$port:$ip" \
            -H "Range: bytes=0-" -A "CFIP-Picker/CLI/1.0" \
            "https://$host$path" 2>/dev/null)
    else
        # 单独先取响应头找 cf-ray
        cf_ray=$(curl -s -D - -o /dev/null -m $((SPEED_TIMEOUT+2)) \
            --resolve "$host:$port:$ip" -H "Host: $host" \
            -H "Range: bytes=0-" -A "CFIP-Picker/CLI/1.0" \
            "https://$host$path" 2>/dev/null | tr -d '\r' | grep -i '^cf-ray:' | head -1 | awk '{print $2}')
        download_speed=$(curl -s -o /dev/null -w '%{speed_download}' \
            --max-time $SPEED_TIMEOUT \
            --resolve "$host:$port:$ip" -H "Host: $host" \
            -H "Range: bytes=0-" -A "CFIP-Picker/CLI/1.0" \
            "https://$host$path" 2>/dev/null)
    fi
    # B/s → Mbps
    mbps=$(awk -v s="${download_speed:-0}" 'BEGIN{printf "%.1f", s*8/1000000}')
    # cf-ray → 机房代码(最后一段大写)
    colo=""
    if [[ -n "$cf_ray" ]]; then
        colo=$(echo "$cf_ray" | grep -oE '[A-Z]{2,5}$' | head -1)
    fi
    echo "$mbps $colo"
}

# 机房代码 → 城市名(locations.json 反查)
lookup_city() {
    local colo="$1"
    [[ -z "$colo" ]] && { echo ""; return; }
    echo "$LOCATIONS_JSON" | grep -oE "\"iata\":\"$colo\",[^}]*" | grep -oE '"city":"[^"]*"' | head -1 | cut -d'"' -f4
}

# ------------------------------ 主流程 ------------------------------
echo "== CF优选IP 命令行版 =="
echo "API: $API_BASE | 期望: ${EXPECTED_SPEED}Mbps 时延≤${EXPECTED_LATENCY}ms | 候选: $COUNT/批 ×$MAX_BATCHES 批 | 测速前$TOP (每IP ${SPEED_TIMEOUT}s)${RANDOM_SELECT:+ | 随机选候选}"
[[ -n "$PREFIX" ]] && echo "前缀过滤: $PREFIX"
[[ $BAIDU_PROXY -eq 1 ]] && echo "百度前置代理: 开启"

log "拉取数据..."
ALL_RANGES=$(api_get "ips-v4" "ips_v4")
LOCATIONS_JSON=$(api_get "locations" "locations")
SPEED_URL=$(api_get "url" "speed_url")
HTTP_TEST_URL=$(api_get "http_test_url" "http_test_url")
[[ -z "$ALL_RANGES" ]] && die "拉取 IP 段失败"
log "IP段: $(echo "$ALL_RANGES" | wc -l) 个 | 测速: $SPEED_URL | 联通: $HTTP_TEST_URL"

# 批次循环
declare -a RESULT_LINES=()
found=0
for ((batch=1; batch<=MAX_BATCHES; batch++)); do
    echo ""
    echo "---- 第 $batch 批 ----"
    generate_pool pool
    [[ ${#pool[@]} -eq 0 ]] && { echo "候选为空,退出"; break; }
    echo "候选 ${#pool[@]} 个(RTT 并发 $BATCH/批)"

    # 1. 并发 RTT 测延迟
    declare -a RTT_LINES=()
    for chunk_start in $(seq 0 $BATCH ${#pool[@]}); do
        chunk=("${pool[@]:chunk_start:BATCH}")
        [[ ${#chunk[@]} -eq 0 ]] && break
        # 并发 RTT:xargs -P
        tmp=$(mktemp)
        printf '%s\n' "${chunk[@]}" | xargs -P"$BATCH" -I{} bash -c '
            ip="$1"
            t0=$(date +%s%N)
            if timeout '"$RTT_TIMEOUT"' bash -c "exec 3<>/dev/tcp/$ip/443" 2>/dev/null; then
                t1=$(date +%s%N)
                echo "$ip $(( (t1 - t0) / 1000000 ))"
            fi
        ' _ {} >> "$tmp"
        while IFS= read -r line; do [[ -n "$line" ]] && RTT_LINES+=("$line"); done < "$tmp"
        rm -f "$tmp"
    done
    log "RTT 存活: ${#RTT_LINES[@]}"

    # 2. 按延迟排序 + 期望时延筛选(mapfile 避免污染全局 IFS)
    mapfile -t RTT_LINES < <(printf '%s\n' "${RTT_LINES[@]}" | sort -k2 -n)
    declare -a SORTED=()
    for line in "${RTT_LINES[@]}"; do
        latency=$(echo "$line" | awk '{print $2}')
        if [[ $EXPECTED_LATENCY -gt 0 ]] && [[ "$latency" -gt $EXPECTED_LATENCY ]]; then
            continue
        fi
        SORTED+=("$line")
    done
    log "时延筛选后: ${#SORTED[@]}"
    [[ ${#SORTED[@]} -eq 0 ]] && { echo "本批 RTT 全挂或全被时延筛掉,下一批"; continue; }

    # 3. 联通性测试(并发)
    if [[ $DO_CONNECTIVITY -eq 1 ]] && [[ -n "$HTTP_TEST_URL" ]]; then
        echo "联通性测试 ${#SORTED[@]} 个..."
        declare -a REACHABLE=()
        export HTTP_TEST_URL
        for chunk_start in $(seq 0 $BATCH ${#SORTED[@]}); do
            chunk=("${SORTED[@]:chunk_start:BATCH}")
            [[ ${#chunk[@]} -eq 0 ]] && break
            tmp=$(mktemp)
            printf '%s\n' "${chunk[@]}" | awk '{print $1}' | xargs -P"$BATCH" -I{} bash -c '
                ip="$1"
                u="${HTTP_TEST_URL#*://}"
                host="${u%%/*}"
                path="${u#*/}"
                [[ "$path" == "$u" ]] && path=""
                [[ -z "$path" ]] && path="/"
                code=$(curl -s -o /dev/null -w "%{http_code}" -m '"$((RTT_TIMEOUT+2))"' \
                    --resolve "$host:443:$ip" -H "Host: $host" -A "CFIP-Picker/CLI/1.0" \
                    "https://$host$path" 2>/dev/null)
                [[ "$code" =~ ^[23] ]] && echo "$ip"
            ' _ {} >> "$tmp"
            while IFS= read -r ip; do
                [[ -z "$ip" ]] && continue
                # 从 SORTED 找回延迟
                for line in "${SORTED[@]}"; do
                    [[ "${line%% *}" == "$ip" ]] && REACHABLE+=("$line") && break
                done
            done < "$tmp"
            rm -f "$tmp"
        done
        SORTED=("${REACHABLE[@]}")
        log "联通性通过: ${#SORTED[@]}"
        [[ ${#SORTED[@]} -eq 0 ]] && { echo "本批全部不通,下一批"; continue; }
    fi

    # 4. 测速候选:默认按 RTT 排序取前 TOP;--random 时从 RTT 有响应的 IP 随机取 TOP 个(对齐 app v1.0.25)
    local -a CANDIDATES=()
    if [[ $RANDOM_SELECT -eq 1 ]]; then
        mapfile -t CANDIDATES < <(printf '%s\n' "${SORTED[@]}" | sort -R | head -"$TOP")
        echo "测速前 ${TOP} 个(随机选择)..."
    else
        CANDIDATES=("${SORTED[@]:0:$TOP}")
        echo "测速前 ${TOP} 个..."
    fi
    declared=0
    for line in "${CANDIDATES[@]}"; do
        ip=$(echo "$line" | awk '{print $1}')
        latency=$(echo "$line" | awk '{print $2}')
        read -r mbps colo < <(speed_one "$ip")
        city=$(lookup_city "$colo")
        log "测速 $ip: ${mbps}Mbps (${latency}ms)"
        RESULT_LINES+=("$latency|$ip|$mbps|$city")
        # 达标即停
        if awk -v m="$mbps" -v e="$EXPECTED_SPEED" 'BEGIN{exit !(m>=e)}'; then
            echo "🎯 达标: $ip ${mbps}Mbps (延迟${latency}ms${city:+ 📍$city}),停止扫描"
            found=1
            break
        fi
    done
    [[ $found -eq 1 ]] && break
    echo "本批未达标,下一批..."
done

# 5. 输出汇总(延迟优先,带宽次之)
echo ""
echo "================ 结果汇总 ================"
[[ ${#RESULT_LINES[@]} -eq 0 ]] && { echo "没有可用结果"; exit 1; }
printf '%s\n' "${RESULT_LINES[@]}" | sort -t'|' -k1 -n -k3 -r | while IFS='|' read -r latency ip mbps city; do
    printf '%-16s 延迟 %-5s ms  带宽 %-7s Mbps  %s\n' "$ip" "$latency" "$mbps" "${city:+📍$city}"
done
echo "=========================================="
exit 0
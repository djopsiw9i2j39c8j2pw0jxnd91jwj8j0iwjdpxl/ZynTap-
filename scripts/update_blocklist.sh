#!/usr/bin/env bash
# Tải + gộp + khử trùng các blocklist công khai vào app/src/main/assets/blocklists/{ads,malware}.txt
# Chạy tự động trong GitHub Actions trước mỗi lần build; cũng có thể chạy tay: bash scripts/update_blocklist.sh
# Nếu một nguồn lỗi thì bỏ qua nguồn đó; nếu cả nhóm quá ít dòng thì giữ nguyên file cũ.
set -uo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/blocklists"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
MIN_DOMAINS=1000

ADS=(
  "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
  "https://adaway.org/hosts.txt"
  "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
)
MALWARE=(
  "https://phishing.army/download/phishing_army_blocklist.txt"
  "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/Alternate%20versions%20Anti-Malware%20List/AntiMalwareHosts.txt"
)

# Dòng hosts ("0.0.0.0 domain") hoặc tên miền trơn → chỉ giữ tên miền hợp lệ, viết thường.
normalize() {
  tr -d '\r' | sed 's/#.*//' | awk 'NF { print tolower($NF) }' \
    | grep -E '^([a-z0-9_-]+\.)+[a-z]{2,}$' | grep -v '^localhost\.localdomain$'
}

# build <tên file> <url...>
build() {
  local name=$1; shift
  local tmp="$TMP/$name"
  : > "$tmp"
  for url in "$@"; do
    curl -fsSL --retry 3 --max-time 120 "$url" | normalize >> "$tmp" || echo "⚠ bỏ qua nguồn lỗi: $url" >&2
  done
  sort -u "$tmp" -o "$tmp"
  local n; n=$(wc -l < "$tmp")
  if [ "$n" -ge "$MIN_DOMAINS" ]; then
    mv "$tmp" "$OUT/$name"
    echo "✔ $name: $n tên miền"
  else
    echo "⚠ $name chỉ có $n tên miền — giữ danh sách cũ" >&2
  fi
}

build ads.txt "${ADS[@]}"
build malware.txt "${MALWARE[@]}"

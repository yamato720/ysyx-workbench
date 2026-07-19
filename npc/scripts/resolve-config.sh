#!/usr/bin/env bash
# 在 Make 选择工具链或源码入口前解析受信任的目录项。
set -euo pipefail

usage() {
  echo "usage: $0 <catalog> <name> <scope[,scope...]>" >&2
  exit 2
}

[[ $# == 3 ]] || usage
catalog=$1
request=$2
allowed_scopes=$3
[[ -f $catalog && -n $request && -n $allowed_scopes ]] || usage

matches=()
while IFS=$'\t' read -r short_name class_name scope board target extra; do
  [[ -z ${short_name:-} || $short_name == \#* ]] && continue
  [[ -z ${extra:-} ]] || { echo "!invalid configuration catalog row"; exit 2; }
  [[ $short_name == "$request" || $class_name == "$request" ]] || continue
  matches+=("$short_name|$class_name|$scope|$board|$target")
done < "$catalog"

[[ ${#matches[@]} == 1 ]] || {
  if [[ ${#matches[@]} == 0 ]]; then echo "!unknown config=$request; add a discoverable complete Scala Config"; else echo "!duplicate config=$request in $catalog"; fi
  exit 2
}

IFS='|' read -r short_name class_name scope board target <<< "${matches[0]}"
case ",$allowed_scopes," in
  *",$scope,"*) ;;
  *) echo "!config=$class_name has scope=$scope; expected one of $allowed_scopes"; exit 2 ;;
esac
[[ $class_name =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]] || {
  echo "!invalid class name $class_name"; exit 2;
}
[[ $target == NPC || $target == SOC ]] || { echo "!invalid target $target"; exit 2; }
[[ $board == - || $board == zcu102 || $board == u55c ]] || { echo "!invalid board $board"; exit 2; }
printf '%s|%s|%s|%s\n' "$class_name" "$scope" "$board" "$target"

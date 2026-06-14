#!/bin/bash
# 卸载/清理脚本

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "===== 停止并卸载 UnlockNeteaseMusic 服务 ====="
docker compose -f "$REPO_DIR/docker-compose.yml" down

echo ""
read -p "是否删除日志和证书文件？[y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    rm -rf "$REPO_DIR/logs" "$REPO_DIR/certs"
    echo "已清理日志和证书"
fi

echo "卸载完成"

#!/bin/bash
# UnlockNeteaseMusic 服务端一键部署脚本
# 适用于已安装 Docker + Docker Compose 的 NAS/Linux 服务器

set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="$REPO_DIR/certs"
LOG_DIR="$REPO_DIR/logs"

echo "===== UnlockNeteaseMusic 服务端部署 ====="

# 1. 创建必要目录
echo "[1/5] 创建目录..."
mkdir -p "$CERT_DIR" "$LOG_DIR"

# 2. 生成自签证书（用于 HTTPS MITM）
if [ ! -f "$CERT_DIR/ca.crt" ] || [ ! -f "$CERT_DIR/ca.key" ]; then
    echo "[2/5] 生成自签 CA 证书..."
    openssl genrsa -out "$CERT_DIR/ca.key" 2048
    openssl req -new -x509 -days 3650 -key "$CERT_DIR/ca.key" \
        -out "$CERT_DIR/ca.crt" \
        -subj "/C=CN/ST=Zhejiang/L=Hangzhou/O=UNM-Proxy/CN=UNM CA"

    openssl genrsa -out "$CERT_DIR/server.key" 2048
    openssl req -new -key "$CERT_DIR/server.key" \
        -out "$CERT_DIR/server.csr" \
        -subj "/C=CN/ST=Zhejiang/L=Hangzhou/O=UNM-Proxy/CN=music.163.com"

    # 生成 extensions 文件
    cat > "$CERT_DIR/ext.cnf" << EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = music.163.com
DNS.2 = interface.music.163.com
DNS.3 = interface3.music.163.com
DNS.4 = interfacepc.music.163.com
DNS.5 = interface.music.163.com.163jiasu.com
DNS.6 = interface3.music.163.com.163jiasu.com
EOF

    openssl x509 -req -days 3650 -in "$CERT_DIR/server.csr" \
        -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
        -CAcreateserial -out "$CERT_DIR/server.crt" \
        -extfile "$CERT_DIR/ext.cnf"

    echo "  CA 证书已生成: $CERT_DIR/ca.crt"
    echo "  服务端证书已生成: $CERT_DIR/server.crt"
else
    echo "[2/5] 证书已存在，跳过生成"
fi

# 3. 拉取最新 Docker 镜像
echo "[3/5] 拉取最新 Docker 镜像..."
docker compose -f "$REPO_DIR/docker-compose.yml" pull

# 4. 启动服务
echo "[4/5] 启动 UnlockNeteaseMusic 服务..."
docker compose -f "$REPO_DIR/docker-compose.yml" up -d

# 5. 等待并验证
echo "[5/5] 验证服务状态..."
sleep 5
if docker compose -f "$REPO_DIR/docker-compose.yml" ps | grep -q "unm-server.*Up"; then
    echo ""
    echo "===== 部署成功 ====="
    echo ""
    echo "HTTP 代理地址:  http://$(hostname -I | awk '{print $1}'):8080"
    echo "HTTPS 代理地址: https://$(hostname -I | awk '{print $1}'):8081"
    echo "PAC 地址:       http://$(hostname -I | awk '{print $1}'):8080/proxy.pac"
    echo ""
    echo "iOS 客户端需要安装 CA 证书:"
    echo "  1. 将 $CERT_DIR/ca.crt 传输到 iOS 设备"
    echo "  2. 设置 > 通用 > VPN与设备管理 > 安装证书"
    echo "  3. 设置 > 通用 > 关于本机 > 证书信任设置 > 启用完全信任"
    echo ""
    echo "查看日志: docker compose -f $REPO_DIR/docker-compose.yml logs -f"
else
    echo "服务启动失败，请检查日志："
    echo "  docker compose -f $REPO_DIR/docker-compose.yml logs"
    exit 1
fi

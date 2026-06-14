/**
 * UNMHook - 网易云音乐解锁 iOS Tweak
 * 
 * 工作原理：
 * - 方案A（默认）：Hook NSURLSessionConfiguration，为网易云的 HTTP 请求设置代理到 UNM 服务器
 * - 方案B（备选）：直接修改请求 URL，将网易云 API 请求重定向到 UNM 服务器
 * - 同时处理 HTTPS 自签证书信任，允许 UNM 的 MITM 证书
 * 
 * 部署方式：
 * - 巨魔（TrollStore）：使用 inject 工具注入到网易云音乐.ipa 后安装
 * - 越狱：放入 /Library/MobileSubstrate/DynamicLibraries/ + .plist
 * 
 * 编译环境：Theos（macOS / Linux）
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

// ============ 配置区域 - 请修改为你的实际值 ============

// UNM 代理服务器地址（你的 NAS IP 或域名）
static NSString *const PROXY_HOST = @"www.zhp0104.fun";
// UNM 代理服务器端口
static NSInteger const PROXY_HTTP_PORT = 8080;
static NSInteger const PROXY_HTTPS_PORT = 8081;

// 工作模式：0=代理模式（推荐），1=URL重写模式
static int const WORK_MODE = 0;

// 是否在通知中心显示调试信息
static BOOL const DEBUG_MODE = YES;

// ============ 以下无需修改 ============

// 需要拦截的域名
static NSArray<NSString *> *TargetDomains;
// EAPI 关键路径
static NSArray<NSString *> *EAPIPaths;

static void UNMLog(NSString *format, ...) NS_FORMAT_FUNCTION(1,2);
static void UNMLog(NSString *format, ...) {
    va_list args;
    va_start(args, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);
    NSLog(@"[UNMHook] %@", message);
    
    if (DEBUG_MODE) {
        dispatch_async(dispatch_get_main_queue(), ^{
            // 可选：发送本地通知用于调试
        });
    }
}

static BOOL IsTargetDomain(NSString *host) {
    if (!host) return NO;
    for (NSString *domain in TargetDomains) {
        if ([host isEqualToString:domain] || [host hasSuffix:[NSString stringWithFormat:@".%@", domain]]) {
            return YES;
        }
    }
    return NO;
}

// ============ 模式A：代理模式（Hook SessionConfiguration）============

%hook NSURLSessionConfiguration

- (NSDictionary *)connectionProxyDictionary {
    NSDictionary *orig = %orig;
    // 检查是否已经是我们的代理配置
    if ([[orig objectForKey:@"HTTPProxy"] objectForKey:@"HTTPProxyHost"] &&
        [[[orig objectForKey:@"HTTPProxy"] objectForKey:@"HTTPProxyHost"] isEqualToString:PROXY_HOST]) {
        return orig;
    }
    
    // 注入代理配置
    NSMutableDictionary *proxyDict = [NSMutableDictionary dictionaryWithDictionary:orig ?: @{}];
    proxyDict[@"HTTPEnable"] = @YES;
    proxyDict[@"HTTPProxy"] = @{
        @"HTTPProxyHost": PROXY_HOST,
        @"HTTPProxyPort": @(PROXY_HTTP_PORT)
    };
    proxyDict[@"HTTPSEnable"] = @YES;
    proxyDict[@"HTTPSProxy"] = @{
        @"HTTPSProxyHost": PROXY_HOST,
        @"HTTPSProxyPort": @(PROXY_HTTPS_PORT)
    };
    // 排除本地地址不走代理
    proxyDict[@"ExceptionsList"] = @[@"localhost", @"127.0.0.1", @"*.local"];
    
    UNMLog(@"Injected proxy: %@:%ld/%ld", PROXY_HOST, (long)PROXY_HTTP_PORT, (long)PROXY_HTTPS_PORT);
    return [proxyDict copy];
}

%end

// ============ 模式B：URL 重写模式（备选）============

%hook NSMutableURLRequest

- (void)setURL:(NSURL *)URL {
    if (WORK_MODE == 1 && URL && IsTargetDomain(URL.host)) {
        // 将请求重写为通过 UNM 代理
        NSString *originalHost = URL.host;
        NSString *originalScheme = URL.scheme;
        NSInteger targetPort = [originalScheme isEqualToString:@"https"] ? PROXY_HTTPS_PORT : PROXY_HTTP_PORT;
        
        NSMutableString *newPath = [NSMutableString stringWithString:URL.path];
        if (URL.query) {
            [newPath appendFormat:@"?%@", URL.query];
        }
        
        NSString *newURLString = [NSString stringWithFormat:@"%@://%@:%ld%@",
                                  originalScheme, PROXY_HOST, (long)targetPort, newPath];
        NSURL *newURL = [NSURL URLWithString:newURLString];
        
        %orig(newURL);
        [self setValue:originalHost forHTTPHeaderField:@"Host"];
        [self setValue:originalHost forHTTPHeaderField:@"X-Original-Host"];
        UNMLog(@"URL rewrite: %@ → %@", URL.absoluteString, newURL.absoluteString);
        return;
    }
    %orig;
}

%end

// ============ HTTPS 自签证书信任 ============

%hook NSURLConnection

+ (BOOL)allowsAnyHTTPSCertificateForHost:(NSString *)host {
    if (IsTargetDomain(host) || [host isEqualToString:PROXY_HOST]) {
        UNMLog(@"Trusting cert for: %@", host);
        return YES;
    }
    return %orig;
}

%end

// Hook NSURLSession 的证书验证回调
// 注意：这个 Hook 需要在 NSURLSessionDelegate 层面处理
// 对于使用自定义 delegate 的请求，需要 Hook delegate 的回调

%hook NSObject

- (void)URLSession:(NSURLSession *)session didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler {
    if ([challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust]) {
        NSString *host = challenge.protectionSpace.host;
        if (IsTargetDomain(host) || [host isEqualToString:PROXY_HOST]) {
            SecTrustRef trust = challenge.protectionSpace.serverTrust;
            if (trust) {
                NSURLCredential *credential = [NSURLCredential credentialForTrust:trust];
                UNMLog(@"Trusted cert via delegate for: %@", host);
                completionHandler(NSURLSessionAuthChallengeUseCredential, credential);
                return;
            }
        }
    }
    %orig;
}

%end

// ============ 初始化 ============

%ctor {
    TargetDomains = @[
        @"music.163.com",
        @"interface.music.163.com",
        @"interface3.music.163.com",
        @"interfacepc.music.163.com",
        @"interface.music.163.com.163jiasu.com",
        @"interface3.music.163.com.163jiasu.com"
    ];
    
    EAPIPaths = @[
        @"song/enhance/player/url",
        @"song/enhance/download/url",
        @"song/enhance/privilege"
    ];
    
    UNMLog(@"=== UNMHook v1.0.0 Loaded ===");
    UNMLog(@"Proxy: %@:%ld/%ld", PROXY_HOST, (long)PROXY_HTTP_PORT, (long)PROXY_HTTPS_PORT);
    UNMLog(@"Mode: %@", WORK_MODE == 0 ? @"Proxy" : @"URL Rewrite");
    UNMLog(@"Target domains: %lu", (unsigned long)TargetDomains.count);
}

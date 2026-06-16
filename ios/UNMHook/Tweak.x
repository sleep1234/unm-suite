/**
 * UNMHook - 网易云音乐解锁 iOS Tweak
 * 
 * 工作原理（原生模式，无需 Node.js）：
 * - Hook NSURLSession 的请求回调
 * - 拦截 song/enhance/player/url 和 song/enhance/download/url 的响应
 * - 对灰色/无权限歌曲，调用 GD studio API 获取真实音源 URL
 * - 替换响应中的 url 字段返回给播放器
 * 
 * 部署方式：
 * - 巨魔（TrollStore）：使用 inject 工具注入到网易云音乐.ipa 后安装
 * - 越狱：放入 /Library/MobileSubstrate/DynamicLibraries/ + .plist
 * 
 * 编译环境：Theos（macOS / Linux）
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

// ============ 配置区域 ============

// 音源 API（pyncmd / GD studio）
static NSString *const MUSIC_API_URL = @"https://music-api.gdstudio.xyz/api.php";

// 音源提供商（可选: kuwo, kugou, qq, pyncmd 等）
static NSString *const SOURCE = @"pyncmd";

// 码率: 999=FLAC, 320=320kbps, 128=128kbps
static NSInteger const BITRATE = 999;

// 是否在日志中输出调试信息
static BOOL const DEBUG_MODE = YES;

// ============ 以下无需修改 ============

static void UNMLog(NSString *format, ...) NS_FORMAT_FUNCTION(1,2);
static void UNMLog(NSString *format, ...) {
    va_list args;
    va_start(args, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);
    NSLog(@"[UNMHook] %@", message);
}

// 需要拦截的 EAPI 路径关键词
static NSArray<NSString *> *EAPIPaths;

// ============ 音源获取 ============

/**
 * 从 GD studio API 获取音源 URL
 * API: https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id={id}&br={br}
 */
static NSString *fetchMusicURL(NSInteger songId) {
    NSString *urlString = [NSString stringWithFormat:@"%@?types=url&source=netease&id=%ld&br=%ld",
                           MUSIC_API_URL, (long)songId, (long)BITRATE];
    NSURL *url = [NSURL URLWithString:urlString];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.timeoutInterval = 10;
    request.HTTPMethod = @"GET";
    [request setValue:@"Mozilla/5.0" forHTTPHeaderField:@"User-Agent"];

    // Synchronous request (called from background thread)
    NSError *error = nil;
    NSURLResponse *response = nil;
    NSData *data = [NSURLConnection sendSynchronousRequest:request returningResponse:&response error:&error];

    if (!data || error) {
        UNMLog(@"API request failed for song %ld: %@", (long)songId, error.localizedDescription);
        return nil;
    }

    NSError *jsonError = nil;
    id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&jsonError];
    if (![json isKindOfClass:[NSDictionary class]]) {
        UNMLog(@"API response not JSON for song %ld: %@", (long)songId, jsonError.localizedDescription);
        return nil;
    }

    NSDictionary *dict = (NSDictionary *)json;
    NSNumber *br = dict[@"br"];
    NSString *musicUrl = dict[@"url"];

    if (br && [br integerValue] > 0 && musicUrl && musicUrl.length > 0) {
        UNMLog(@"Got music URL for song %ld, br=%ld", (long)songId, (long)[br integerValue]);
        return musicUrl;
    }

    UNMLog(@"API returned empty/no-url for song %ld", (long)songId);
    return nil;
}

/**
 * 从 URL 中提取歌曲 ID
 * EAPI URL pattern: /eapi/song/enhance/player/url  with body containing {"ids":[123456,...]}
 * We extract the id from the JSON body data before it's encrypted.
 */
static NSArray *extractSongIds(NSData *bodyData) {
    if (!bodyData || bodyData.length == 0) return nil;

    // Try to parse as JSON first
    NSError *error = nil;
    id json = [NSJSONSerialization JSONObjectWithData:bodyData options:0 error:&error];
    if ([json isKindOfClass:[NSDictionary class]]) {
        NSArray *ids = [(NSDictionary *)json objectForKey:@"ids"];
        if ([ids isKindOfClass:[NSArray class]] && ids.count > 0) {
            return ids;
        }
        // Also try "id" (singular)
        NSNumber *id = [(NSDictionary *)json objectForKey:@"id"];
        if (id) {
            return @[id];
        }
    }

    return nil;
}

// ============ Hook NSURLSession 拦截 EAPI 响应 ============

%hook NSURLSession

- (NSURLSessionDataTask *)dataTaskWithRequest:(NSURLRequest *)request
                            completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler {
    NSString *path = request.URL.path;
    NSString *urlString = request.URL.absoluteString;

    // Check if this is an EAPI player URL request
    BOOL isTarget = NO;
    for (NSString *apiPath in EAPIPaths) {
        if ([path containsString:apiPath]) {
            isTarget = YES;
            break;
        }
    }

    if (!isTarget) {
        return %orig;
    }

    UNMLog(@"Intercepting player URL request: %@", path);

    void (^newCompletionHandler)(NSData *, NSURLResponse *, NSError *) = ^(NSData *data, NSURLResponse *response, NSError *error) {
        if (error || !data) {
            completionHandler(data, response, error);
            return;
        }

        // Try to parse response and inject music URLs for grayed-out songs
        @try {
            NSError *parseError = nil;
            id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&parseError];
            if ([json isKindOfClass:[NSDictionary class]]) {
                NSMutableDictionary *mutableJson = [(NSDictionary *)json mutableCopy];
                NSMutableDictionary *dataDict = mutableJson[@"data"];
                BOOL modified = NO;

                // Handle array format: {"data": [{"id": 123, "url": null, ...}, ...]}
                if ([dataDict isKindOfClass:[NSDictionary class]]) {
                    modified = injectMusicURLIntoDict(dataDict) || modified;
                } else if ([dataDict isKindOfClass:[NSArray class]]) {
                    // Array format - not common but handle it
                    NSMutableArray *mutableArray = [(NSArray *)dataDict mutableCopy];
                    for (int i = 0; i < mutableArray.count; i++) {
                        if ([mutableArray[i] isKindOfClass:[NSDictionary class]]) {
                            NSMutableDictionary *songDict = [(NSDictionary *)mutableArray[i] mutableCopy];
                            if (injectMusicURLIntoDict(songDict)) {
                                mutableArray[i] = songDict;
                                modified = YES;
                            }
                        }
                    }
                    mutableJson[@"data"] = mutableArray;
                }

                if (modified) {
                    NSData *newData = [NSJSONSerialization dataWithJSONObject:mutableJson options:0 error:nil];
                    if (newData) {
                        UNMLog(@"Response modified - injected music URLs");
                        completionHandler(newData, response, nil);
                        return;
                    }
                }
            }
        } @catch (NSException *exception) {
            UNMLog(@"Error processing response: %@", exception.reason);
        }

        completionHandler(data, response, error);
    };

    return %orig(request, newCompletionHandler);
}

%end

// ============ 音源注入辅助函数 ============

static BOOL injectMusicURLIntoDict(NSMutableDictionary *songDict) {
    // Check if song has no playable URL
    id urlObj = songDict[@"url"];
    NSNumber *fee = songDict[@"fee"];
    NSNumber *songId = songDict[@"id"];

    // Only inject if url is null/empty or song is VIP-only (fee > 0)
    BOOL needInject = NO;
    if ((!urlObj || [urlObj isKindOfClass:[NSNull class]] ||
         ([urlObj isKindOfClass:[NSString class]] && [(NSString *)urlObj length] == 0))) {
        needInject = YES;
    }
    // Also inject if fee == 1 (VIP song) even if url exists (might be low quality)
    if (fee && [fee integerValue] == 1) {
        needInject = YES;
    }

    if (!needInject || !songId) {
        return NO;
    }

    NSInteger sid = [songId integerValue];
    if (sid <= 0) return NO;

    UNMLog(@"Song %ld needs URL injection", (long)sid);

    NSString *musicUrl = fetchMusicURL(sid);
    if (musicUrl && musicUrl.length > 0) {
        songDict[@"url"] = musicUrl;
        // Update quality info
        songDict[@"br"] = @(BITRATE * 1000);
        songDict[@"size"] = @0;
        songDict[@"type"] = (BITRATE == 999) ? @"flac" : @"mp3";
        // Mark as free to play
        songDict[@"fee"] = @0;
        songDict[@"pl"] = @320000;
        songDict[@"dl"] = @320000;
        return YES;
    }

    return NO;
}

// ============ 初始化 ============

%ctor {
    EAPIPaths = @[
        @"song/enhance/player/url",
        @"song/enhance/download/url"
    ];

    UNMLog(@"=== UNMHook v2.0.0 Loaded (Native Mode) ===");
    UNMLog(@"API: %@", MUSIC_API_URL);
    UNMLog(@"Source: %@, Bitrate: %ld", SOURCE, (long)BITRATE);
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

/**
 * UNMHook - 网易云音乐解锁 iOS Tweak (Pure ObjC, no Theos/Substrate)
 * 
 * 工作原理（原生模式，无需 Node.js / 代理服务器）：
 * - Hook NSURLSession 的请求回调
 * - 拦截 song/enhance/player/url 和 song/enhance/download/url 的响应
 * - 对灰色/无权限歌曲，调用 GD studio API 获取真实音源 URL
 * - 替换响应中的 url 字段返回给播放器
 * 
 * 编译：clang++ -dynamiclib -arch arm64 -isysroot $(xcrun --sdk iphoneos --show-sdk-path) \
 *       -framework Foundation -framework UIKit -fobjc-arc -fmodules \
 *       -install_name "@rpath/libUNMHook.dylib" -o UNMHook.dylib Tweak.x
 * 
 * 部署方式：
 * - 巨魔（TrollStore）：使用 inject 工具注入到网易云音乐.ipa 后安装
 * - 越狱：放入 /Library/MobileSubstrate/DynamicLibraries/ + .plist
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>
#import <objc/message.h>

// ============ 配置区域 ============

// 音源 API（pyncmd / GD studio）
static NSString *const MUSIC_API_URL = @"https://music-api.gdstudio.xyz/api.php";

// 码率: 999=FLAC, 320=320kbps, 128=128kbps
static NSInteger const BITRATE = 999;

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

// ============ 音源获取（同步，使用信号量） ============

static NSString *fetchMusicURL(NSInteger songId) {
    NSString *urlString = [NSString stringWithFormat:@"%@?types=url&source=netease&id=%ld&br=%ld",
                           MUSIC_API_URL, (long)songId, (long)BITRATE];
    NSURL *url = [NSURL URLWithString:urlString];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.timeoutInterval = 10;
    request.HTTPMethod = @"GET";
    [request setValue:@"Mozilla/5.0" forHTTPHeaderField:@"User-Agent"];

    // 用信号量实现同步请求（替代已废弃的 NSURLConnection）
    __block NSData *responseData = nil;
    __block NSError *responseError = nil;
    dispatch_semaphore_t sema = dispatch_semaphore_create(0);

    NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:config];
    NSURLSessionDataTask *task = [session dataTaskWithRequest:request
                                            completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        responseData = data;
        responseError = error;
        dispatch_semaphore_signal(sema);
    }];
    [task resume];

    dispatch_semaphore_wait(sema, dispatch_time(DISPATCH_TIME_NOW, 12 * NSEC_PER_SEC));

    if (!responseData || responseError) {
        UNMLog(@"API request failed for song %ld: %@", (long)songId, responseError.localizedDescription);
        return nil;
    }

    NSError *jsonError = nil;
    id json = [NSJSONSerialization JSONObjectWithData:responseData options:0 error:&jsonError];
    if (![json isKindOfClass:[NSDictionary class]]) {
        UNMLog(@"API response not JSON for song %ld", (long)songId);
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

// ============ 音源注入 ============

static BOOL injectMusicURLIntoDict(NSMutableDictionary *songDict) {
    NSNumber *songId = songDict[@"id"];
    if (!songId || [songId integerValue] <= 0) {
        return NO;
    }

    NSInteger sid = [songId integerValue];

    // 判断是否需要注入：只要是 VIP/试听/无 URL 都注入
    id urlObj = songDict[@"url"];
    NSNumber *fee = songDict[@"fee"];
    NSNumber *pl = songDict[@"pl"];

    BOOL needInject = NO;

    // 1. URL 为空/null
    if (!urlObj || [urlObj isKindOfClass:[NSNull class]] ||
        ([urlObj isKindOfClass:[NSString class]] && [(NSString *)urlObj length] == 0)) {
        needInject = YES;
    }
    // 2. fee 标记为 VIP（1=VIP, 4=购买专辑）
    if (fee && ([fee integerValue] == 1 || [fee integerValue] == 4)) {
        needInject = YES;
    }
    // 3. pl（可播放码率）为 0 或很小，说明无法完整播放
    if (pl && [pl integerValue] <= 0) {
        needInject = YES;
    }

    if (!needInject) {
        // 即使有 URL，也可能是试听片段（30s），检查码率
        // 试听片段通常 br 很低或 size 很小
        NSNumber *br = songDict[@"br"];
        NSNumber *size = songDict[@"size"];
        NSString *urlStr = (urlObj && [urlObj isKindOfClass:[NSString class]]) ? (NSString *)urlObj : nil;

        // 如果 URL 包含试听标记，或者 br/size 异常小
        if (urlStr && [urlStr containsString:@"trial"]) {
            needInject = YES;
        }
        if (br && [br integerValue] > 0 && [br integerValue] < 128000) {
            // 码率低于 128k，可能是试听
            needInject = YES;
        }
    }

    if (!needInject) {
        return NO;
    }

    UNMLog(@"Song %ld needs URL injection (fee=%@, url=%@, br=%@, pl=%@)", 
           (long)sid, fee, 
           (urlObj && [urlObj isKindOfClass:[NSString class]]) ? urlObj : @"(nil)",
           songDict[@"br"], pl);

    NSString *musicUrl = fetchMusicURL(sid);
    if (musicUrl && musicUrl.length > 0) {
        songDict[@"url"] = musicUrl;
        songDict[@"br"] = @(BITRATE * 1000);
        songDict[@"size"] = @0;
        songDict[@"type"] = (BITRATE == 999) ? @"flac" : @"mp3";
        songDict[@"fee"] = @0;
        songDict[@"pl"] = @320000;
        songDict[@"dl"] = @320000;
        songDict[@"flag"] = @0;
        return YES;
    }

    return NO;
}

// ============ Hook NSURLSession 拦截 EAPI 响应 ============

// 保存原始方法实现
static IMP orig_dataTaskWithRequest_completionHandler = NULL;

// 用 @implementation 分类声明替换方法（纯 ObjC 必须在 @implementation 内声明方法）
@interface NSURLSession (UNMHook)
- (NSURLSessionDataTask *)unm_dataTaskWithRequest:(NSURLRequest *)request
                                completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler;
@end

@implementation NSURLSession (UNMHook)

- (NSURLSessionDataTask *)unm_dataTaskWithRequest:(NSURLRequest *)request
                                completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler {
    NSString *path = request.URL.path;

    BOOL isTarget = NO;
    for (NSString *apiPath in EAPIPaths) {
        if ([path containsString:apiPath]) {
            isTarget = YES;
            break;
        }
    }

    if (!isTarget) {
        // 非目标请求，直接调原方法
        return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *)))
                orig_dataTaskWithRequest_completionHandler)(self, _cmd, request, completionHandler);
    }

    UNMLog(@"Intercepting player URL request: %@", path);

    // 调试：弹窗显示拦截到的请求路径
    dispatch_async(dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController
            alertControllerWithTitle:@"UNMHook 拦截"
            message:[NSString stringWithFormat:@"请求路径:\n%@", path]
            preferredStyle:UIAlertControllerStyleAlert];
        UIAlertAction *ok = [UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil];
        [alert addAction:ok];

        UIWindowScene *scene = nil;
        for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) { scene = s; break; }
        UIViewController *rootVC = nil;
        if (scene) { for (UIWindow *w in scene.windows) { if (w.isKeyWindow) { rootVC = w.rootViewController; break; } } }
        while (rootVC.presentedViewController) { rootVC = rootVC.presentedViewController; }
        if (rootVC) { [rootVC presentViewController:alert animated:YES completion:nil]; }
    });

    void (^newCompletionHandler)(NSData *, NSURLResponse *, NSError *) = ^(NSData *data, NSURLResponse *response, NSError *error) {
        if (error || !data) {
            completionHandler(data, response, error);
            return;
        }

        @try {
            NSError *parseError = nil;
            id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&parseError];
            if ([json isKindOfClass:[NSDictionary class]]) {
                NSMutableDictionary *mutableJson = [(NSDictionary *)json mutableCopy];
                NSMutableDictionary *dataDict = mutableJson[@"data"];
                BOOL modified = NO;

                if ([dataDict isKindOfClass:[NSDictionary class]]) {
                    // 调试：弹窗显示原始响应关键字段
                    NSNumber *origFee = dataDict[@"fee"];
                    NSNumber *origBr = dataDict[@"br"];
                    NSNumber *origPl = dataDict[@"pl"];
                    NSString *origUrl = dataDict[@"url"];
                    if (origUrl && ![origUrl isKindOfClass:[NSString class]]) origUrl = @"(not string)";
                    NSString *debugInfo = [NSString stringWithFormat:@"id=%@\nfee=%@\nbr=%@\npl=%@\nurl=%@",
                        dataDict[@"id"], origFee, origBr, origPl,
                        origUrl ? [origUrl substringToIndex:MIN(80, [origUrl length])] : @"(nil)"];
                    dispatch_async(dispatch_get_main_queue(), ^{
                        UIAlertController *alert = [UIAlertController
                            alertControllerWithTitle:@"UNMHook 原始响应"
                            message:debugInfo
                            preferredStyle:UIAlertControllerStyleAlert];
                        UIAlertAction *ok = [UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil];
                        [alert addAction:ok];
                        UIWindowScene *scene = nil;
                        for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) { scene = s; break; }
                        UIViewController *rootVC = nil;
                        if (scene) { for (UIWindow *w in scene.windows) { if (w.isKeyWindow) { rootVC = w.rootViewController; break; } } }
                        while (rootVC.presentedViewController) { rootVC = rootVC.presentedViewController; }
                        if (rootVC) { [rootVC presentViewController:alert animated:YES completion:nil]; }
                    });

                    modified = injectMusicURLIntoDict(dataDict) || modified;
                } else if ([dataDict isKindOfClass:[NSArray class]]) {
                    NSMutableArray *mutableArray = [(NSArray *)dataDict mutableCopy];
                    for (NSUInteger i = 0; i < mutableArray.count; i++) {
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

    return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *)))
            orig_dataTaskWithRequest_completionHandler)(self, _cmd, request, newCompletionHandler);
}

@end

// ============ 构造函数：安装 Hook ============

__attribute__((constructor)) static void UNMHookInit() {
    EAPIPaths = @[
        @"song/enhance/player/url",
        @"song/enhance/download/url"
    ];

    // 方法交换：NSURLSession.dataTaskWithRequest:completionHandler:
    Class cls = [NSURLSession class];
    SEL originalSelector = @selector(dataTaskWithRequest:completionHandler:);
    SEL swizzledSelector = @selector(unm_dataTaskWithRequest:completionHandler:);

    Method originalMethod = class_getInstanceMethod(cls, originalSelector);
    Method swizzledMethod = class_getInstanceMethod(cls, swizzledSelector);

    if (originalMethod && swizzledMethod) {
        // 保存原始实现
        orig_dataTaskWithRequest_completionHandler = method_getImplementation(originalMethod);
        // 交换实现
        method_exchangeImplementations(originalMethod, swizzledMethod);
        UNMLog(@"Hook installed: NSURLSession.dataTaskWithRequest:completionHandler:");
    } else {
        UNMLog(@"ERROR: Failed to find methods for hooking");
    }

    UNMLog(@"=== UNMHook v2.0.0 Loaded (Native Mode, No Theos) ===");
    UNMLog(@"API: %@", MUSIC_API_URL);
    UNMLog(@"Bitrate: %ld", (long)BITRATE);

    // 弹窗确认加载成功（调试用，确认后可移除）
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController alertControllerWithTitle:@"UNMHook"
                                                                       message:@"v2.0.0 已加载！音源解锁已启用"
                                                                preferredStyle:UIAlertControllerStyleAlert];
        UIAlertAction *ok = [UIAlertAction actionWithTitle:@"好的"
                                                     style:UIAlertActionStyleDefault
                                                   handler:nil];
        [alert addAction:ok];

        // 获取当前可见的 ViewController
        UIWindowScene *scene = nil;
        for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) {
            if (s.activationState == UISceneActivationStateForegroundActive) {
                scene = s;
                break;
            }
        }
        if (!scene) {
            for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) {
                scene = s;
                break;
            }
        }

        UIViewController *rootVC = nil;
        if (scene) {
            for (UIWindow *w in scene.windows) {
                if (w.isKeyWindow) {
                    rootVC = w.rootViewController;
                    break;
                }
            }
        }
        if (!rootVC) {
            rootVC = [UIApplication sharedApplication].keyWindow.rootViewController;
        }

        // 找到最顶层的 ViewController
        while (rootVC.presentedViewController) {
            rootVC = rootVC.presentedViewController;
        }

        if (rootVC) {
            [rootVC presentViewController:alert animated:YES completion:nil];
        }
    });
}

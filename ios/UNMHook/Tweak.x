/**
 * UNMHook v2.1.0 - Debug Build
 * Hook ALL NSURLSession dataTask methods, log every request URL to file,
 * and show popup for any music-related requests.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>
#import <objc/message.h>

static NSString *const MUSIC_API_URL = @"https://music-api.gdstudio.xyz/api.php";
static NSInteger const BITRATE = 999;
static NSString *const LOG_FILE = @"/tmp/unmhook_debug.log";

static void UNMLog(NSString *format, ...) NS_FORMAT_FUNCTION(1,2);
static void UNMLog(NSString *format, ...) {
    va_list args;
    va_start(args, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);
    NSLog(@"[UNMHook] %@", message);
    // Also write to file
    NSString *line = [NSString stringWithFormat:@"[%@] %@\n",
        [NSDateFormatter localizedStringFromDate:[NSDate date]
                                        dateStyle:NSDateFormatterNoStyle
                                        timeStyle:NSDateFormatterMediumStyle],
        message];
    NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:LOG_FILE];
    if (fh) {
        [fh seekToEndOfFile];
        [fh writeData:[line dataUsingEncoding:NSUTF8StringEncoding]];
        [fh closeFile];
    } else {
        [line writeToFile:LOG_FILE atomically:YES encoding:NSUTF8StringEncoding error:nil];
    }
}

// 弹窗辅助函数
static void showAlert(NSString *title, NSString *message) {
    dispatch_async(dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController
            alertControllerWithTitle:title
            message:message
            preferredStyle:UIAlertControllerStyleAlert];
        UIAlertAction *ok = [UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil];
        [alert addAction:ok];

        UIWindowScene *scene = nil;
        for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) { scene = s; break; }
        UIViewController *rootVC = nil;
        if (scene) {
            for (UIWindow *w in scene.windows) {
                if (w.isKeyWindow) { rootVC = w.rootViewController; break; }
            }
        }
        while (rootVC.presentedViewController) { rootVC = rootVC.presentedViewController; }
        if (rootVC) { [rootVC presentViewController:alert animated:YES completion:nil];
        } else {
            UNMLog(@"WARN: Could not find rootVC for alert");
        }
    });
}

// ============ 音源获取 ============

static NSString *fetchMusicURL(NSInteger songId) {
    NSString *urlString = [NSString stringWithFormat:@"%@?types=url&source=netease&id=%ld&br=%ld",
                           MUSIC_API_URL, (long)songId, (long)BITRATE];
    NSURL *url = [NSURL URLWithString:urlString];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.timeoutInterval = 10;
    request.HTTPMethod = @"GET";
    [request setValue:@"Mozilla/5.0" forHTTPHeaderField:@"User-Agent"];

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
    if (!songId || [songId integerValue] <= 0) return NO;

    NSInteger sid = [songId integerValue];
    id urlObj = songDict[@"url"];
    NSNumber *fee = songDict[@"fee"];
    NSNumber *pl = songDict[@"pl"];

    BOOL needInject = NO;

    if (!urlObj || [urlObj isKindOfClass:[NSNull class]] ||
        ([urlObj isKindOfClass:[NSString class]] && [(NSString *)urlObj length] == 0)) {
        needInject = YES;
    }
    if (fee && ([fee integerValue] == 1 || [fee integerValue] == 4)) {
        needInject = YES;
    }
    if (pl && [pl integerValue] <= 0) {
        needInject = YES;
    }
    if (!needInject) {
        NSNumber *br = songDict[@"br"];
        NSString *urlStr = (urlObj && [urlObj isKindOfClass:[NSString class]]) ? (NSString *)urlObj : nil;
        if (urlStr && [urlStr containsString:@"trial"]) needInject = YES;
        if (br && [br integerValue] > 0 && [br integerValue] < 128000) needInject = YES;
    }

    if (!needInject) return NO;

    UNMLog(@"Song %ld needs URL injection (fee=%@, br=%@, pl=%@)", (long)sid, fee, songDict[@"br"], pl);

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

// ============ Hook ALL NSURLSession dataTask 方法 ============

static IMP orig_dataTaskWithRequest_completionHandler = NULL;
static IMP orig_dataTaskWithURL_completionHandler = NULL;

@interface NSURLSession (UNMHook)
- (NSURLSessionDataTask *)unm_dataTaskWithRequest:(NSURLRequest *)request
                                completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler;
- (NSURLSessionDataTask *)unm_dataTaskWithURL:(NSURL *)url
                             completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler;
@end

@implementation NSURLSession (UNMHook)

// 是否是需要拦截的 API
static BOOL isTargetPath(NSString *path) {
    if (!path) return NO;
    NSArray *keywords = @[@"song/enhance/player", @"song/enhance/download",
                          @"song/enhance", @"player/url", @"download/url"];
    for (NSString *kw in keywords) {
        if ([path containsString:kw]) return YES;
    }
    return NO;
}

// 是否是音乐相关请求（更宽泛的匹配，用于调试日志）
static BOOL isMusicRelated(NSString *path) {
    if (!path) return NO;
    NSArray *keywords = @[@"song", @"player", "/music/", "enhance", "download/url", "eapi"];
    for (NSString *kw in keywords) {
        if ([path containsString:kw]) return YES;
    }
    return NO;
}

// 处理拦截到的响应数据
static void processResponseData(NSData *data, NSURLResponse *response, NSError *error,
                                 void (^completionHandler)(NSData *, NSURLResponse *, NSError *),
                                 NSString *requestPath) {
    if (error || !data) {
        completionHandler(data, response, error);
        return;
    }

    @try {
        NSError *parseError = nil;
        id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&parseError];
        if (![json isKindOfClass:[NSDictionary class]]) {
            completionHandler(data, response, error);
            return;
        }

        NSMutableDictionary *mutableJson = [(NSDictionary *)json mutableCopy];
        NSMutableDictionary *dataDict = mutableJson[@"data"];
        BOOL modified = NO;

        if ([dataDict isKindOfClass:[NSDictionary class]]) {
            // 调试弹窗：显示原始响应
            NSNumber *origFee = dataDict[@"fee"];
            NSNumber *origBr = dataDict[@"br"];
            NSNumber *origPl = dataDict[@"pl"];
            NSString *origUrl = dataDict[@"url"];
            if (origUrl && ![origUrl isKindOfClass:[NSString class]]) origUrl = @"(not string)";
            NSString *origId = [NSString stringWithFormat:@"%@", dataDict[@"id"]];
            NSString *urlPreview = origUrl ? [(NSString *)origUrl substringToIndex:MIN(60, [(NSString *)origUrl length])] : @"(nil)";
            NSString *debugInfo = [NSString stringWithFormat:@"path=%@\nid=%@\nfee=%@\nbr=%@\npl=%@\nurl=%@",
                requestPath, origId, origFee, origBr, origPl, urlPreview];
            showAlert(@"UNMHook 拦截响应", debugInfo);

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
                UNMLog(@"Response modified - injected music URLs for %@", requestPath);
                completionHandler(newData, response, nil);
                return;
            }
        }
    } @catch (NSException *exception) {
        UNMLog(@"Error processing response: %@", exception.reason);
    }

    completionHandler(data, response, error);
}

- (NSURLSessionDataTask *)unm_dataTaskWithRequest:(NSURLRequest *)request
                                completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler {
    NSString *path = request.URL.path;

    // 记录所有请求
    UNMLog(@"[REQ] %@", path);

    // 对音乐相关请求弹窗
    if (isMusicRelated(path)) {
        UNMLog(@"[MUSIC] %@", path);
        showAlert(@"UNMHook 请求", [NSString stringWithFormat:@"%@", path]);
    }

    BOOL target = isTargetPath(path);
    if (!target) {
        return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *)))
                orig_dataTaskWithRequest_completionHandler)(self, _cmd, request, completionHandler);
    }

    UNMLog(@"[TARGET] Intercepting: %@", path);

    void (^wrappedHandler)(NSData *, NSURLResponse *, NSError *) = ^(NSData *data, NSURLResponse *response, NSError *error) {
        processResponseData(data, response, error, completionHandler, path);
    };

    return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *)))
            orig_dataTaskWithRequest_completionHandler)(self, _cmd, request, wrappedHandler);
}

- (NSURLSessionDataTask *)unm_dataTaskWithURL:(NSURL *)url
                             completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))completionHandler {
    NSString *path = url.path;

    UNMLog(@"[REQ-URL] %@", path);

    if (isMusicRelated(path)) {
        UNMLog(@"[MUSIC-URL] %@", path);
        showAlert(@"UNMHook 请求(URL)", [NSString stringWithFormat:@"%@", path]);
    }

    BOOL target = isTargetPath(path);
    if (!target) {
        return ((NSURLSessionDataTask *(*)(id, SEL, NSURL *, void (^)(NSData *, NSURLResponse *, NSError *)))
                orig_dataTaskWithURL_completionHandler)(self, _cmd, url, completionHandler);
    }

    UNMLog(@"[TARGET-URL] Intercepting: %@", path);

    void (^wrappedHandler)(NSData *, NSURLResponse *, NSError *) = ^(NSData *data, NSURLResponse *response, NSError *error) {
        processResponseData(data, response, error, completionHandler, path);
    };

    return ((NSURLSessionDataTask *(*)(id, SEL, NSURL *, void (^)(NSData *, NSURLResponse *, NSError *)))
            orig_dataTaskWithURL_completionHandler)(self, _cmd, url, wrappedHandler);
}

@end

// ============ 构造函数 ============

__attribute__((constructor)) static void UNMHookInit() {
    // 清空日志文件
    [@"" writeToFile:LOG_FILE atomically:YES encoding:NSUTF8StringEncoding error:nil];

    Class cls = [NSURLSession class];

    // Hook 1: dataTaskWithRequest:completionHandler:
    SEL sel1 = @selector(dataTaskWithRequest:completionHandler:);
    SEL swiz1 = @selector(unm_dataTaskWithRequest:completionHandler:);
    Method m1 = class_getInstanceMethod(cls, sel1);
    Method sm1 = class_getInstanceMethod(cls, swiz1);
    if (m1 && sm1) {
        orig_dataTaskWithRequest_completionHandler = method_getImplementation(m1);
        method_exchangeImplementations(m1, sm1);
        UNMLog(@"Hook 1 installed: dataTaskWithRequest:completionHandler:");
    } else {
        UNMLog(@"ERROR: Hook 1 failed - methods not found");
    }

    // Hook 2: dataTaskWithURL:completionHandler:
    SEL sel2 = @selector(dataTaskWithURL:completionHandler:);
    SEL swiz2 = @selector(unm_dataTaskWithURL:completionHandler:);
    Method m2 = class_getInstanceMethod(cls, sel2);
    Method sm2 = class_getInstanceMethod(cls, swiz2);
    if (m2 && sm2) {
        orig_dataTaskWithURL_completionHandler = method_getImplementation(m2);
        method_exchangeImplementations(m2, sm2);
        UNMLog(@"Hook 2 installed: dataTaskWithURL:completionHandler:");
    } else {
        UNMLog(@"WARN: Hook 2 - dataTaskWithURL:completionHandler: not found (may not exist on this OS)");
    }

    UNMLog(@"=== UNMHook v2.1.0 Debug Loaded ===");
    UNMLog(@"Log file: %@", LOG_FILE);

    // 启动弹窗
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController
            alertControllerWithTitle:@"UNMHook v2.1.0 Debug"
            message:@"已加载！播放VIP歌曲时将弹窗显示请求信息。\n日志文件: /tmp/unmhook_debug.log"
            preferredStyle:UIAlertControllerStyleAlert];
        UIAlertAction *ok = [UIAlertAction actionWithTitle:@"好的" style:UIAlertActionStyleDefault handler:nil];
        [alert addAction:ok];

        UIWindowScene *scene = nil;
        for (UIWindowScene *s in [UIApplication sharedApplication].connectedScenes) { scene = s; break; }
        UIViewController *rootVC = nil;
        if (scene) { for (UIWindow *w in scene.windows) { if (w.isKeyWindow) { rootVC = w.rootViewController; break; } } }
        while (rootVC.presentedViewController) { rootVC = rootVC.presentedViewController; }
        if (rootVC) { [rootVC presentViewController:alert animated:YES completion:nil]; }
    });
}

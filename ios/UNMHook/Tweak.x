/**
 * UNMHook v2.2.0 - Debug Build
 * Hook ALL NSURLSession dataTask methods (with AND without completionHandler)
 * + Hook delegate callback methods
 * + Log every request URL to file
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

static void showAlert(NSString *title, NSString *message) {
    dispatch_async(dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController
            alertControllerWithTitle:title message:message
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
    if (![json isKindOfClass:[NSDictionary class]]) return nil;
    NSDictionary *dict = (NSDictionary *)json;
    NSNumber *br = dict[@"br"];
    NSString *musicUrl = dict[@"url"];
    if (br && [br integerValue] > 0 && musicUrl && musicUrl.length > 0) {
        UNMLog(@"Got music URL for song %ld, br=%ld", (long)songId, (long)[br integerValue]);
        return musicUrl;
    }
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
        ([urlObj isKindOfClass:[NSString class]] && [(NSString *)urlObj length] == 0)) needInject = YES;
    if (fee && ([fee integerValue] == 1 || [fee integerValue] == 4)) needInject = YES;
    if (pl && [pl integerValue] <= 0) needInject = YES;
    if (!needInject) {
        NSNumber *br = songDict[@"br"];
        NSString *urlStr = (urlObj && [urlObj isKindOfClass:[NSString class]]) ? (NSString *)urlObj : nil;
        if (urlStr && [urlStr containsString:@"trial"]) needInject = YES;
        if (br && [br integerValue] > 0 && [br integerValue] < 128000) needInject = YES;
    }
    if (!needInject) return NO;

    UNMLog(@"Song %ld needs injection (fee=%@, br=%@, pl=%@)", (long)sid, fee, songDict[@"br"], pl);
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

// ============ Hook NSURLSession (4个 dataTask 方法) ============

static IMP orig_req_ch = NULL;  // dataTaskWithRequest:completionHandler:
static IMP orig_url_ch = NULL;  // dataTaskWithURL:completionHandler:
static IMP orig_req    = NULL;  // dataTaskWithRequest: (no completionHandler - delegate mode)
static IMP orig_url    = NULL;  // dataTaskWithURL: (no completionHandler - delegate mode)

@interface NSURLSession (UNMHook)
- (NSURLSessionDataTask *)unm_req_ch:(NSURLRequest *)request completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))handler;
- (NSURLSessionDataTask *)unm_url_ch:(NSURL *)url completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))handler;
- (NSURLSessionDataTask *)unm_req:(NSURLRequest *)request;
- (NSURLSessionDataTask *)unm_url:(NSURL *)url;
@end

static BOOL isMusicRelated(NSString *path) {
    if (!path) return NO;
    NSArray *kw = @[@"song", @"player", @"enhance", @"download/url", @"eapi", @"music"];
    for (NSString *k in kw) { if ([path containsString:k]) return YES; }
    return NO;
}

static void processResponse(NSData *data, NSURLResponse *response, NSError *error,
                            void (^handler)(NSData *, NSURLResponse *, NSError *), NSString *path) {
    if (error || !data) { handler(data, response, error); return; }
    @try {
        NSError *pe = nil;
        id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&pe];
        if (![json isKindOfClass:[NSDictionary class]]) { handler(data, response, error); return; }

        NSMutableDictionary *mj = [(NSDictionary *)json mutableCopy];
        NSMutableDictionary *dd = mj[@"data"];
        BOOL mod = NO;

        if ([dd isKindOfClass:[NSDictionary class]]) {
            NSNumber *f = dd[@"fee"], *b = dd[@"br"], *p = dd[@"pl"];
            NSString *u = dd[@"url"];
            if (u && ![u isKindOfClass:[NSString class]]) u = @"(not str)";
            NSString *preview = u ? [(NSString *)u substringToIndex:MIN(60, [(NSString *)u length])] : @"(nil)";
            showAlert(@"UNMHook 响应", [NSString stringWithFormat:@"%@\nid=%@ fee=%@ br=%@ pl=%@\nurl=%@",
                path, dd[@"id"], f, b, p, preview]);
            mod = injectMusicURLIntoDict(dd) || mod;
        } else if ([dd isKindOfClass:[NSArray class]]) {
            NSMutableArray *ma = [(NSArray *)dd mutableCopy];
            for (NSUInteger i = 0; i < ma.count; i++) {
                if ([ma[i] isKindOfClass:[NSDictionary class]]) {
                    NSMutableDictionary *sd = [(NSDictionary *)ma[i] mutableCopy];
                    if (injectMusicURLIntoDict(sd)) { ma[i] = sd; mod = YES; }
                }
            }
            mj[@"data"] = ma;
        }
        if (mod) {
            NSData *nd = [NSJSONSerialization dataWithJSONObject:mj options:0 error:nil];
            if (nd) { handler(nd, response, nil); return; }
        }
    } @catch (NSException *e) {
        UNMLog(@"Error: %@", e.reason);
    }
    handler(data, response, error);
}

@implementation NSURLSession (UNMHook)

- (NSURLSessionDataTask *)unm_req_ch:(NSURLRequest *)request completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))handler {
    NSString *path = request.URL.path;
    UNMLog(@"[REQ+CH] %@", path);
    if (isMusicRelated(path)) {
        showAlert(@"UNMHook 请求", path);
    }
    // 对目标路径包装 handler
    if ([path containsString:@"song/enhance"] || [path containsString:@"player/url"] || [path containsString:@"download/url"]) {
        UNMLog(@"[TARGET] %@", path);
        void (^wh)(NSData *, NSURLResponse *, NSError *) = ^(NSData *d, NSURLResponse *r, NSError *e) {
            processResponse(d, r, e, handler, path);
        };
        return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, id))orig_req_ch)(self, _cmd, request, wh);
    }
    return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *, id))orig_req_ch)(self, _cmd, request, handler);
}

- (NSURLSessionDataTask *)unm_url_ch:(NSURL *)url completionHandler:(void (^)(NSData *, NSURLResponse *, NSError *))handler {
    NSString *path = url.path;
    UNMLog(@"[URL+CH] %@", path);
    if (isMusicRelated(path)) {
        showAlert(@"UNMHook 请求(URL+CH)", path);
    }
    return ((NSURLSessionDataTask *(*)(id, SEL, NSURL *, id))orig_url_ch)(self, _cmd, url, handler);
}

- (NSURLSessionDataTask *)unm_req:(NSURLRequest *)request {
    NSString *path = request.URL.path;
    UNMLog(@"[REQ-DELEGATE] %@", path);
    if (isMusicRelated(path)) {
        UNMLog(@"[MUSIC-DELEGATE] %@", path);
        showAlert(@"UNMHook Delegate请求", path);
    }
    return ((NSURLSessionDataTask *(*)(id, SEL, NSURLRequest *))orig_req)(self, _cmd, request);
}

- (NSURLSessionDataTask *)unm_url:(NSURL *)url {
    NSString *path = url.path;
    UNMLog(@"[URL-DELEGATE] %@", path);
    return ((NSURLSessionDataTask *(*)(id, SEL, NSURL *))orig_url)(self, _cmd, url);
}

@end

// ============ 构造函数 ============

static void swizzle(Class cls, SEL origSel, SEL newSel, IMP *origImp) {
    Method om = class_getInstanceMethod(cls, origSel);
    Method nm = class_getInstanceMethod(cls, newSel);
    if (om && nm) {
        *origImp = method_getImplementation(om);
        method_exchangeImplementations(om, nm);
        UNMLog(@"Hook OK: %@", NSStringFromSelector(origSel));
    } else {
        UNMLog(@"Hook SKIP: %@ (orig=%d, new=%d)", NSStringFromSelector(origSel), om!=nil, nm!=nil);
    }
}

__attribute__((constructor)) static void UNMHookInit() {
    [@"" writeToFile:LOG_FILE atomically:YES encoding:NSUTF8StringEncoding error:nil];

    Class cls = [NSURLSession class];

    // 带 completionHandler 的版本
    swizzle(cls, @selector(dataTaskWithRequest:completionHandler:),
            @selector(unm_req_ch:completionHandler:), &orig_req_ch);
    swizzle(cls, @selector(dataTaskWithURL:completionHandler:),
            @selector(unm_url_ch:completionHandler:), &orig_url_ch);

    // 不带 completionHandler 的版本（delegate 模式）
    swizzle(cls, @selector(dataTaskWithRequest:),
            @selector(unm_req:), &orig_req);
    swizzle(cls, @selector(dataTaskWithURL:),
            @selector(unm_url:), &orig_url);

    UNMLog(@"=== UNMHook v2.2.0 Debug Loaded ===");

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        UIAlertController *alert = [UIAlertController
            alertControllerWithTitle:@"UNMHook v2.2.0"
            message:@"已加载！Hook了4个dataTask方法(含delegate模式)。\n播放时看弹窗，日志: /tmp/unmhook_debug.log"
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

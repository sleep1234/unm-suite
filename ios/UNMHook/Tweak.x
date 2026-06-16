/**
 * UNMHook v3.0.0 - NSJSONSerialization Hook
 * 
 * Strategy: Hook NSJSONSerialization at the JSON parsing layer.
 * This works regardless of which HTTP library the app uses internally,
 * because all EAPI responses are eventually deserialized from JSON.
 * 
 * - Hooks JSONObjectWithData:options:error: (parsing bytes->object)
 * - Detects player URL responses by checking for song data structure
 * - Calls GD Studio API to fetch full-length URLs for VIP songs
 * - Removes fee/trial metadata, replaces URLs
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>

static NSString *const GD_API_URL = @"https://music-api.gdstudio.xyz/api.php";
static NSInteger const BITRATE = 999;

// Simple log to NSLog only (no file I/O overhead)
#define UNMLog(fmt, ...) NSLog(@"[UNMHook] " fmt, ##__VA_ARGS__)

// ============ GD API Music URL Fetching ============

// In-memory cache: songId -> {url, timestamp}
static NSMutableDictionary *gdCache = nil;

@interface _GDURLCache : NSObject
@property (nonatomic, copy) NSString *url;
@property (nonatomic, assign) NSTimeInterval timestamp;
@end
@implementation _GDURLCache
@end

static NSTimeInterval const CACHE_TTL = 1800; // 30 minutes

static NSString *cachedURLForSong(NSInteger songId) {
    @synchronized(gdCache) {
        _GDURLCache *entry = gdCache[@(songId)];
        if (entry && ([NSDate timeIntervalSinceReferenceDate] - entry.timestamp) < CACHE_TTL) {
            return entry.url;
        }
        if (entry) [gdCache removeObjectForKey:@(songId)];
    }
    return nil;
}

static void setCachedURL(NSInteger songId, NSString *url) {
    @synchronized(gdCache) {
        _GDURLCache *entry = [[_GDURLCache alloc] init];
        entry.url = url;
        entry.timestamp = [NSDate timeIntervalSinceReferenceDate];
        gdCache[@(songId)] = entry;
    }
}

static NSString *fetchMusicURL(NSInteger songId) {
    // Check cache first
    NSString *cached = cachedURLForSong(songId);
    if (cached) {
        UNMLog(@"Cache hit for song %ld", (long)songId);
        return cached;
    }

    NSString *urlString = [NSString stringWithFormat:@"%@?types=url&source=netease&id=%ld&br=%ld",
                           GD_API_URL, (long)songId, (long)BITRATE];
    NSURL *url = [NSURL URLWithString:urlString];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.timeoutInterval = 8;
    request.HTTPMethod = @"GET";
    [request setValue:@"Mozilla/5.0" forHTTPHeaderField:@"User-Agent"];

    // Synchronous request via semaphore
    __block NSData *responseData = nil;
    __block NSError *responseError = nil;
    dispatch_semaphore_t sema = dispatch_semaphore_create(0);
    NSURLSessionConfiguration *config = [NSURLSessionConfiguration ephemeralSessionConfiguration];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:config];
    NSURLSessionDataTask *task = [session dataTaskWithRequest:request
                                            completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        responseData = data;
        responseError = error;
        dispatch_semaphore_signal(sema);
    }];
    [task resume];
    dispatch_semaphore_wait(sema, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));

    if (!responseData || responseError) {
        UNMLog(@"API failed for song %ld: %@", (long)songId, responseError.localizedDescription);
        return nil;
    }

    NSError *jsonError = nil;
    id json = [NSJSONSerialization JSONObjectWithData:responseData options:0 error:&jsonError];
    if (![json isKindOfClass:[NSDictionary class]]) return nil;

    NSDictionary *dict = (NSDictionary *)json;
    NSString *musicUrl = dict[@"url"];
    if (musicUrl && [musicUrl isKindOfClass:[NSString class]] && musicUrl.length > 0 &&
        [musicUrl hasPrefix:@"http"]) {
        UNMLog(@"API OK song %ld, url length=%lu", (long)songId, (unsigned long)musicUrl.length);
        setCachedURL(songId, musicUrl);
        return musicUrl;
    }

    return nil;
}

// ============ Song Data Injection ============

/**
 * Check if a dictionary looks like a player URL response (single song).
 * Expected keys: id, url, fee, br, type, etc.
 */
static BOOL isPlayerURLDict(NSDictionary *dict) {
    if (!dict) return NO;
    return (dict[@"id"] != nil && dict[@"url"] != nil);
}

/**
 * Process a single song data dictionary — replace URL if needed.
 * Returns YES if modified.
 */
static BOOL processSongDict(NSMutableDictionary *songDict) {
    NSNumber *songId = songDict[@"id"];
    if (!songId || [songId integerValue] <= 0) return NO;

    NSNumber *fee = songDict[@"fee"];
    NSNumber *freeTrialInfo = songDict[@"freeTrialInfo"];

    // Only process VIP/trial songs
    BOOL isVIP = (fee && ([fee integerValue] == 1 || [fee integerValue] == 4));
    BOOL hasTrial = (freeTrialInfo && [freeTrialInfo isKindOfClass:[NSDictionary class]]);
    if (!isVIP && !hasTrial) return NO;

    NSInteger sid = [songId integerValue];
    UNMLog(@"Processing VIP song %ld (fee=%@, hasTrial=%d)", (long)sid, fee, hasTrial);

    NSString *musicUrl = fetchMusicURL(sid);
    if (musicUrl) {
        songDict[@"url"] = musicUrl;
        songDict[@"br"] = @(BITRATE * 1000);
        songDict[@"size"] = @0;
        songDict[@"type"] = @"flac";
        songDict[@"level"] = @"lossless";
        songDict[@"fee"] = @0;
        songDict[@"flag"] = @0;
        songDict[@"payed"] = @0;
        songDict[@"freeTrialInfo"] = [NSNull null];
        return YES;
    }
    return NO;
}

/**
 * Try to modify a player URL response JSON object in-place.
 * Handles both {"data": {...}} and {"data": [{...}]} formats.
 * Returns modified JSON data, or nil if unchanged.
 */
static NSData *tryModifyPlayerResponse(id jsonObj) {
    @try {
        NSMutableDictionary *root = nil;
        if ([jsonObj isKindOfClass:[NSMutableDictionary class]]) {
            root = (NSMutableDictionary *)jsonObj;
        } else if ([jsonObj isKindOfClass:[NSDictionary class]]) {
            root = [(NSDictionary *)jsonObj mutableCopy];
        } else {
            return nil;
        }

        id dataObj = root[@"data"];
        BOOL modified = NO;

        if ([dataObj isKindOfClass:[NSDictionary class]]) {
            // Single song: {"data": {"id":..., "url":..., "fee":...}}
            if (isPlayerURLDict((NSDictionary *)dataObj)) {
                NSMutableDictionary *songData = [(NSDictionary *)dataObj mutableCopy];
                if (processSongDict(songData)) {
                    root[@"data"] = songData;
                    modified = YES;
                }
            }
        } else if ([dataObj isKindOfClass:[NSArray class]]) {
            // Batch songs: {"data": [{...}, {...}]}
            NSMutableArray *arr = [(NSArray *)dataObj mutableCopy];
            for (NSUInteger i = 0; i < arr.count; i++) {
                if ([arr[i] isKindOfClass:[NSDictionary class]] && isPlayerURLDict(arr[i])) {
                    NSMutableDictionary *songData = [(NSDictionary *)arr[i] mutableCopy];
                    if (processSongDict(songData)) {
                        arr[i] = songData;
                        modified = YES;
                    }
                }
            }
            root[@"data"] = arr;
        }

        if (modified) {
            return [NSJSONSerialization dataWithJSONObject:root options:0 error:nil];
        }
    } @catch (NSException *e) {
        UNMLog(@"Exception in tryModifyPlayerResponse: %@", e.reason);
    }
    return nil;
}

// ============ NSJSONSerialization Hook ============

static IMP orig_JSONObjectWithData = NULL;

@interface NSJSONSerialization (UNMHook)
+ (id)unm_JSONObjectWithData:(NSData *)data options:(NSJSONReadingOptions)opts error:(NSError **)error;
@end

@implementation NSJSONSerialization (UNMHook)

+ (id)unm_JSONObjectWithData:(NSData *)data options:(NSJSONReadingOptions)opts error:(NSError **)error {
    // Call original
    id result = ((id (*)(id, SEL, NSData *, NSJSONReadingOptions, NSError **))orig_JSONObjectWithData)(
        self, _cmd, data, opts, error);

    if (!result || ![result isKindOfClass:[NSDictionary class]]) return result;

    NSDictionary *dict = (NSDictionary *)result;
    NSNumber *code = dict[@"code"];
    
    // Only process successful responses with "data" key containing song info
    // Typical player URL response: {"code": 200, "data": [{"id":..., "url":..., "fee":...}]}
    if (code && [code integerValue] == 200 && dict[@"data"] != nil) {
        id dataObj = dict[@"data"];
        
        // Quick check: does data contain player URL structure?
        BOOL mightBePlayer = NO;
        if ([dataObj isKindOfClass:[NSDictionary class]]) {
            mightBePlayer = isPlayerURLDict((NSDictionary *)dataObj);
        } else if ([dataObj isKindOfClass:[NSArray class]]) {
            NSArray *arr = (NSArray *)dataObj;
            if (arr.count > 0 && [arr[0] isKindOfClass:[NSDictionary class]]) {
                mightBePlayer = isPlayerURLDict(arr[0]);
            }
        }
        
        if (mightBePlayer) {
            // Parse again to get mutable copy and try to modify
            @try {
                NSError *pe = nil;
                id freshJson = [NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:&pe];
                if (freshJson) {
                    NSData *modifiedData = tryModifyPlayerResponse(freshJson);
                    if (modifiedData) {
                        UNMLog(@"Modified player URL response");
                        // Re-parse the modified data and return it
                        NSError *re = nil;
                        id modifiedObj = [NSJSONSerialization JSONObjectWithData:modifiedData options:0 error:&re];
                        if (modifiedObj) {
                            return modifiedObj;
                        }
                    }
                }
            } @catch (NSException *e) {
                UNMLog(@"Exception re-parsing: %@", e.reason);
            }
        }
    }

    return result;
}

@end

// ============ Constructor ============

__attribute__((constructor)) static void UNMHookInit() {
    // Initialize cache
    gdCache = [[NSMutableDictionary alloc] init];

    Class cls = [NSJSONSerialization class];
    SEL origSel = @selector(JSONObjectWithData:options:error:);
    Method origMethod = class_getInstanceMethod(cls, origSel);

    // Add our replacement method
    class_addMethod(cls, @selector(unm_JSONObjectWithData:options:error:),
                    (IMP)class_getMethodImplementation(cls, @selector(unm_JSONObjectWithData:options:error:)),
                    method_getTypeEncoding(origMethod));

    // Swizzle
    Method newMethod = class_getInstanceMethod(cls, @selector(unm_JSONObjectWithData:options:error:));
    if (origMethod && newMethod) {
        orig_JSONObjectWithData = method_getImplementation(origMethod);
        method_exchangeImplementations(origMethod, newMethod);
        UNMLog(@"=== UNMHook v3.0.0 loaded - NSJSONSerialization hook active ===");
    } else {
        UNMLog(@"UNMHook v3.0.0 FAILED to swizzle NSJSONSerialization");
    }

    // Startup notification (no alert popup in production)
    UNMLog(@"=== UNMHook v3.0.0 ready ===");
}

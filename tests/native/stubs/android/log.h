#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
int __android_log_print(int priority, const char *tag, const char *fmt, ...);

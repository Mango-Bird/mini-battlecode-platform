/* Trusted native harness. No quickjs-libc, std/os, host callbacks or module loader.
 * The kernel filter is installed before parsing any untrusted input.
 * JS_Eval is the native compiler API in this isolated process, not a JS eval sandbox.
 */
#define _GNU_SOURCE
#include "quickjs.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <math.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define HEAP (32u*1024u*1024u)
static size_t allocated;
static int heap_exceeded;
typedef union { size_t size; max_align_t alignment; } Header;
static void *allocate(void *opaque,size_t size) {
    (void)opaque;
    if(size>HEAP || allocated>HEAP-size) {heap_exceeded=1;return NULL;}
    if(size>SIZE_MAX-sizeof(Header))return NULL;
    Header *h=malloc(sizeof(Header)+size);
    if(!h)return NULL;
    h->size=size;allocated+=size;return h+1;
}
static void release(void *opaque,void *ptr) {
    (void)opaque;if(ptr){Header *h=(Header *)ptr-1;allocated-=h->size;free(h);}
}
static void *resize(void *opaque,void *ptr,size_t size) {
    if(!ptr)return allocate(opaque,size);
    if(!size){release(opaque,ptr);return NULL;}
    Header *old=(Header *)ptr-1;size_t previous=old->size;
    if(size>HEAP || allocated-previous>HEAP-size){heap_exceeded=1;return NULL;}
    Header *h=realloc(old,sizeof(Header)+size);if(!h)return NULL;
    h->size=size;allocated=allocated-previous+size;return h+1;
}
static void *zero_allocate(void *opaque,size_t count,size_t size) {
    if(size && count>SIZE_MAX/size){heap_exceeded=1;return NULL;}
    void *ptr=allocate(opaque,count*size);if(ptr)memset(ptr,0,count*size);return ptr;
}
static size_t usable(const void *ptr){return ptr?((const Header *)ptr-1)->size:0;}
static void failure(const char *code,const char *evidence) {
    printf("{\"failure\":\"%s\",\"evidence\":\"%s\"}\n",code,evidence);fflush(stdout);_exit(0);
}
static void timer_signal(int signal) {
    static const char cpu[]="{\"failure\":\"BOT_TIMEOUT\",\"evidence\":\"KERNEL_PROCESS_CPU_TIMER\"}\n";
    static const char wall[]="{\"failure\":\"INTERNAL_ERROR\",\"evidence\":\"WALL_EXPIRED_WITHOUT_CPU_PROOF\"}\n";
    const char *text=signal==SIGXCPU?cpu:wall;
    size_t len=signal==SIGXCPU?sizeof(cpu)-1:sizeof(wall)-1;
    (void)write(STDOUT_FILENO,text,len);_exit(0);
}
static int timers(void) {
    struct sigaction action={0};action.sa_handler=timer_signal;sigemptyset(&action.sa_mask);
    if(sigaction(SIGXCPU,&action,NULL)||sigaction(SIGALRM,&action,NULL))return -1;
    timer_t cpu,wall;struct sigevent event={0};event.sigev_notify=SIGEV_SIGNAL;event.sigev_signo=SIGXCPU;
    if(timer_create(CLOCK_PROCESS_CPUTIME_ID,&event,&cpu))return -1;
    event.sigev_signo=SIGALRM;
    if(timer_create(CLOCK_MONOTONIC,&event,&wall))return -1;
    struct itimerspec budget={0};budget.it_value.tv_nsec=250000000;
    if(timer_settime(cpu,0,&budget,NULL))return -1;
    budget.it_value.tv_sec=1;budget.it_value.tv_nsec=0;
    return timer_settime(wall,0,&budget,NULL);
}
#define ALLOW(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(n),0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
static int restrict_kernel(void) {
    struct sock_filter rules[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_X86_64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        ALLOW(SYS_read),ALLOW(SYS_write),ALLOW(SYS_readv),ALLOW(SYS_writev),ALLOW(SYS_close),ALLOW(SYS_fstat),
        ALLOW(SYS_mmap),ALLOW(SYS_munmap),ALLOW(SYS_mremap),ALLOW(SYS_brk),ALLOW(SYS_mprotect),
        ALLOW(SYS_rt_sigaction),ALLOW(SYS_rt_sigprocmask),ALLOW(SYS_rt_sigreturn),
        ALLOW(SYS_clock_gettime),ALLOW(SYS_timer_create),ALLOW(SYS_timer_settime),ALLOW(SYS_timer_delete),
        ALLOW(SYS_getpid),ALLOW(SYS_gettid),ALLOW(SYS_futex),ALLOW(SYS_sched_yield),
        ALLOW(SYS_exit),ALLOW(SYS_exit_group),ALLOW(SYS_pause),ALLOW(SYS_dup),
        /* Every other syscall, including open/socket/fork/exec/ptrace/mount, is denied. */
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
    };
    struct sock_fprog program={sizeof(rules)/sizeof(rules[0]),rules};
    return prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)||prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program);
}
static long control(const char *path) {
    FILE *file=fopen(path,"r");long value=-1;
    if(file){if(fscanf(file,"%ld",&value)!=1)value=-1;fclose(file);}return value;
}
static void probe(void) {
    struct statvfs filesystem;struct rlimit fds;
    int readonly=statvfs("/",&filesystem)==0 && (filesystem.f_flag&ST_RDONLY);
    int descriptor=getrlimit(RLIMIT_NOFILE,&fds)==0 && fds.rlim_cur==32 && fds.rlim_max==32;
    long memory=control("/sys/fs/cgroup/memory.max"),swap=control("/sys/fs/cgroup/memory.swap.max"),pids=control("/sys/fs/cgroup/pids.max");
    int nonroot=geteuid()==65534,namespace=getpid()==1;
    if(restrict_kernel())failure("RUNNER_UNAVAILABLE","SECCOMP_INSTALL_FAILED");
    errno=0;int file=syscall(SYS_openat,AT_FDCWD,"/etc/shadow",O_RDONLY,0);int fs=file==-1&&errno==EPERM;
    errno=0;int socket=syscall(SYS_socket,AF_INET,SOCK_STREAM,0);int net=socket==-1&&errno==EPERM;
    errno=0;int child=syscall(SYS_fork);int process=child==-1&&errno==EPERM;
    errno=0;int exec=syscall(SYS_execve,"/guest",NULL,NULL);int execute=exec==-1&&errno==EPERM;
    int copies[32],count=0;while(count<32){int fd=dup(0);if(fd<0)break;copies[count++]=fd;}
    int fdlimit=errno==EMFILE&&count<=29;for(int i=0;i<count;i++)close(copies[i]);
    printf("{\"os\":true,\"filesystem\":%s,\"network\":%s,\"process\":%s,\"exec\":%s,\"readonly\":%s,\"nonroot\":%s,\"pidNamespace\":%s,\"fileDescriptors\":%s,\"environment\":%s,\"memory\":%ld,\"swap\":%ld,\"pids\":%ld}\n",
        fs?"true":"false",net?"true":"false",process?"true":"false",execute?"true":"false",readonly?"true":"false",
        nonroot?"true":"false",namespace?"true":"false",descriptor&&fdlimit?"true":"false",
        !environ||!*environ?"true":"false",memory,swap,pids);
    fflush(stdout);_exit(0);
}
static int exact_read(void *buffer,size_t count) {
    size_t done=0;while(done<count){ssize_t n=read(0,(char *)buffer+done,count-done);if(n<=0)return -1;done+=(size_t)n;}return 0;
}
static JSModuleDef *deny_module(JSContext *ctx,const char *name,void *opaque) {
    (void)name;(void)opaque;JS_ThrowTypeError(ctx,"Modules unavailable");return NULL;
}
static void remove_property(JSContext *ctx,JSValueConst object,const char *name) {
    JSAtom atom=JS_NewAtom(ctx,name);JS_DeleteProperty(ctx,object,atom,0);JS_FreeAtom(ctx,atom);
}
static int shape(JSContext *ctx,JSValueConst object,const char *a,const char *b) {
    if(!JS_IsObject(object)||JS_IsArray(object)||JS_IsProxy(object))return 0;
    JSPropertyEnum *properties=NULL;uint32_t count=0;
    if(JS_GetOwnPropertyNames(ctx,&properties,&count,object,JS_GPN_STRING_MASK|JS_GPN_SYMBOL_MASK)<0)return 0;
    int first=0,second=0;
    for(uint32_t i=0;i<count;i++){const char *name=JS_AtomToCString(ctx,properties[i].atom);
        if(name){first+=strcmp(name,a)==0;second+=strcmp(name,b)==0;JS_FreeCString(ctx,name);}
        JS_FreeAtom(ctx,properties[i].atom);
    }
    js_free(ctx,properties);return count==2&&first==1&&second==1;
}
static JSValue data(JSContext *ctx,JSValueConst object,const char *name) {
    JSPropertyDescriptor descriptor={0};JSAtom atom=JS_NewAtom(ctx,name);
    int found=JS_GetOwnProperty(ctx,&descriptor,object,atom);JS_FreeAtom(ctx,atom);
    if(found!=1)return JS_UNDEFINED;
    if((descriptor.flags&JS_PROP_TMASK)==JS_PROP_GETSET){
        JS_FreeValue(ctx,descriptor.value);JS_FreeValue(ctx,descriptor.getter);JS_FreeValue(ctx,descriptor.setter);return JS_UNDEFINED;
    }
    return descriptor.value;
}
static int integer(JSContext *ctx,JSValueConst value,int32_t *result) {
    double number;
    if(!JS_IsNumber(value)||JS_ToFloat64(ctx,&number,value)<0||!isfinite(number)
        ||number<INT32_MIN||number>INT32_MAX||floor(number)!=number)return 0;
    *result=(int32_t)number;return 1;
}
static int freeze(JSContext *ctx,JSValueConst value,int depth) {
    if(!JS_IsObject(value))return 0;
    if(depth>8)return -1;
    JSPropertyEnum *properties=NULL;uint32_t count=0;
    if(JS_GetOwnPropertyNames(ctx,&properties,&count,value,JS_GPN_STRING_MASK)<0)return -1;
    for(uint32_t i=0;i<count;i++){
        JSValue child=JS_GetProperty(ctx,value,properties[i].atom);
        if(freeze(ctx,child,depth+1)<0)return -1;
        JS_FreeValue(ctx,child);
        if(JS_DefineProperty(ctx,value,properties[i].atom,JS_UNDEFINED,JS_UNDEFINED,JS_UNDEFINED,
            JS_PROP_HAS_WRITABLE|JS_PROP_HAS_CONFIGURABLE)<0)return -1;
        JS_FreeAtom(ctx,properties[i].atom);
    }
    js_free(ctx,properties);return JS_PreventExtensions(ctx,value);
}
static int plain(JSContext *ctx,JSValueConst value,JSValueConst expectedPrototype) {
    if(!JS_IsObject(value)||JS_IsProxy(value)||JS_IsArray(value))return 0;
    JSValue prototype=JS_GetPrototype(ctx,value);
    int accepted=JS_IsNull(prototype)||JS_VALUE_GET_PTR(prototype)==JS_VALUE_GET_PTR(expectedPrototype);
    JS_FreeValue(ctx,prototype);return accepted;
}
int main(int argc,char **argv) {
    clearenv();setvbuf(stdout,NULL,_IONBF,0);
    if(argc==2&&!strcmp(argv[1],"--probe-os"))probe();
    if(restrict_kernel())failure("RUNNER_UNAVAILABLE","SECCOMP_INSTALL_FAILED");
    puts("{\"stage\":\"READY\"}");
    if(argc==2&&!strcmp(argv[1],"--probe-memory")){
        volatile char *p=mmap(NULL,256u*1024u*1024u,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(p==MAP_FAILED)failure("INTERNAL_ERROR","MEMORY_PROBE_FAILED");
        for(size_t i=0;i<256u*1024u*1024u;i+=4096)p[i]=1;
        failure("INTERNAL_ERROR","MEMORY_LIMIT_MISSING");
    }
    if(argc==2&&!strcmp(argv[1],"--probe-cpu")){if(timers())failure("RUNNER_UNAVAILABLE","TIMER_SETUP_FAILED");for(;;){__asm__ volatile("");}}
    if(argc==2&&!strcmp(argv[1],"--probe-wall")){if(timers())failure("RUNNER_UNAVAILABLE","TIMER_SETUP_FAILED");for(;;)pause();}
    uint8_t header[4];if(exact_read(header,4))failure("INTERNAL_ERROR","TRUNCATED_INPUT");
    size_t length=((size_t)header[0]<<24)|((size_t)header[1]<<16)|((size_t)header[2]<<8)|header[3];
    if(!length||length>131072)failure("INTERNAL_ERROR","INPUT_CAP");
    char *input=malloc(length+1);if(!input||exact_read(input,length))failure("INTERNAL_ERROR","INPUT_FAILURE");input[length]=0;
    if(timers())failure("RUNNER_UNAVAILABLE","TIMER_SETUP_FAILED");
    const JSMallocFunctions allocator={zero_allocate,allocate,release,resize,usable};
    JSRuntime *runtime=JS_NewRuntime2(&allocator,NULL);if(!runtime)failure("INTERNAL_ERROR","RUNTIME_SETUP_FAILED");
    JS_SetMaxStackSize(runtime,1024u*1024u);JS_SetModuleLoaderFunc(runtime,NULL,deny_module,NULL);
    JSContext *ctx=JS_NewContextRaw(runtime);if(!ctx)failure("INTERNAL_ERROR","CONTEXT_SETUP_FAILED");
    if(JS_AddIntrinsicBaseObjects(ctx)||JS_AddIntrinsicEval(ctx)||JS_AddIntrinsicJSON(ctx)||JS_AddIntrinsicMapSet(ctx)
        ||JS_AddIntrinsicTypedArrays(ctx)||JS_AddIntrinsicRegExp(ctx))failure("INTERNAL_ERROR","INTRINSIC_SETUP_FAILED");
    JS_AddIntrinsicRegExpCompiler(ctx);
    /* Remove every intrinsic dynamic-code constructor before evaluating user source. */
    const char *constructors[]={"(function*(){})","(async function(){})","(async function*(){})"};
    for(size_t i=0;i<3;i++){
        JSValue sample=JS_Eval(ctx,constructors[i],strlen(constructors[i]),"trusted",JS_EVAL_TYPE_GLOBAL);
        if(JS_IsException(sample))failure("INTERNAL_ERROR","CONSTRUCTOR_SETUP");
        JSValue parent=JS_GetPrototype(ctx,sample);
        while(JS_IsObject(parent)) {
            remove_property(ctx,parent,"constructor");
            JSValue next=JS_GetPrototype(ctx,parent);JS_FreeValue(ctx,parent);parent=next;
        }
        JS_FreeValue(ctx,parent);JS_FreeValue(ctx,sample);
    }
    JSValue global=JS_GetGlobalObject(ctx),function=JS_GetPropertyStr(ctx,global,"Function");
    JSValue prototype=JS_GetPropertyStr(ctx,function,"prototype");remove_property(ctx,prototype,"constructor");
    remove_property(ctx,global,"Function");remove_property(ctx,global,"eval");
    JSValue math=JS_GetPropertyStr(ctx,global,"Math");remove_property(ctx,math,"random");
    JSValue objectConstructor=JS_GetPropertyStr(ctx,global,"Object"),objectPrototype=JS_GetPropertyStr(ctx,objectConstructor,"prototype");
    JSValue request=JS_ParseJSON(ctx,input,length,"input"),source=JS_GetPropertyStr(ctx,request,"source"),
        observation=JS_GetPropertyStr(ctx,request,"observation");
    if(JS_IsException(request)||!JS_IsString(source)||!JS_IsObject(observation))failure("INTERNAL_ERROR","INPUT_SCHEMA");
    if(freeze(ctx,observation,0)<0)failure("INTERNAL_ERROR","OBSERVATION_FREEZE");
    size_t source_length;const char *source_text=JS_ToCStringLen(ctx,&source_length,source);
    if(!source_text||source_length==0||source_length>32768)failure("INTERNAL_ERROR","SOURCE_CAP");
    JSValue evaluated=JS_Eval(ctx,source_text,source_length,"bot",JS_EVAL_TYPE_GLOBAL);
    if(JS_IsException(evaluated))failure(heap_exceeded?"BOT_RESOURCE_LIMIT":"BOT_RUNTIME_ERROR",heap_exceeded?"NATIVE_HEAP_CAP":"JS_EXCEPTION");
    JSValue choose=JS_GetPropertyStr(ctx,global,"chooseMove");
    if(!JS_IsFunction(ctx,choose))failure("BOT_RUNTIME_ERROR","MISSING_CHOOSE_MOVE");
    JSValue action=JS_Call(ctx,choose,JS_UNDEFINED,1,&observation);
    if(JS_IsException(action))failure(heap_exceeded?"BOT_RESOURCE_LIMIT":"BOT_RUNTIME_ERROR",heap_exceeded?"NATIVE_HEAP_CAP":"JS_EXCEPTION");
    if(!plain(ctx,action,objectPrototype)||!shape(ctx,action,"pieceId","to"))failure("INVALID_ACTION","STRICT_ACTION_SCHEMA");
    JSValue id=data(ctx,action,"pieceId"),to=data(ctx,action,"to");
    if(!JS_IsString(id)||!plain(ctx,to,objectPrototype)||!shape(ctx,to,"row","col"))failure("INVALID_ACTION","STRICT_ACTION_SCHEMA");
    JSValue row=data(ctx,to,"row"),col=data(ctx,to,"col");int32_t r,c;
    if(!integer(ctx,row,&r)||!integer(ctx,col,&c))failure("INVALID_ACTION","STRICT_ACTION_SCHEMA");
    size_t id_length;const char *id_text=JS_ToCStringLen(ctx,&id_length,id);
    if(!id_text||!id_length)failure("INVALID_ACTION","STRICT_ACTION_SCHEMA");
    if(id_length>950)failure("BOT_RESOURCE_LIMIT","ACTION_OUTPUT_CAP");
    /* Serialize only approved primitive properties, never user toJSON/getters/JSON.stringify. */
    char escaped[1024];size_t n=0;
    for(size_t i=0;i<id_length;i++){
        unsigned char ch=id_text[i];
        if(n+7>=sizeof(escaped))failure("BOT_RESOURCE_LIMIT","ACTION_OUTPUT_CAP");
        if(ch=='"'||ch=='\\'){escaped[n++]='\\';escaped[n++]=(char)ch;}
        else if(ch<32){n+=(size_t)snprintf(escaped+n,sizeof(escaped)-n,"\\u%04x",ch);}
        else escaped[n++]=(char)ch;
    }
    escaped[n]=0;char output[1100];
    int bytes=snprintf(output,sizeof(output),"{\"pieceId\":\"%s\",\"to\":{\"row\":%d,\"col\":%d}}",escaped,r,c);
    if(bytes<1||bytes>1024)failure("BOT_RESOURCE_LIMIT","ACTION_OUTPUT_CAP");
    printf("{\"action\":%s}\n",output);return 0;
}

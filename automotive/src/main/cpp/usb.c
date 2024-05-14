// based on https://github.com/magnusja/libaums/blob/develop/libaums/src/c/usb.c

#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <errno.h>
#include <string.h>

#pragma clang diagnostic push
#pragma ide diagnostic ignored "UnusedParameter"

JNIEXPORT jint JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_resetUSBNative(JNIEnv *env,
                                                                                                   jobject thiz,
                                                                                                   jint fd) {
    int ret = ioctl(fd, USBDEVFS_RESET);
    return ret;
}

JNIEXPORT jint JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_clearHaltNative(JNIEnv *env,
                                                                                                    jobject thiz,
                                                                                                    jint fd,
                                                                                                    jint endpoint) {
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &endpoint);
    return ret;
}

JNIEXPORT jint JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_getErrorNumberNative(JNIEnv *env,
                                                                                                         jobject thiz) {
    return errno;
}

JNIEXPORT jstring JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_getErrorStringNative(JNIEnv *env,
                                                                                                         jobject thiz,
                                                                                                         jint errorNumber) {
    char *error = strerror(errorNumber);
    return (*env)->NewStringUTF(env, error);
}

#pragma clang diagnostic pop

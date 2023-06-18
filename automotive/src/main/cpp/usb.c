#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

JNIEXPORT jboolean JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_resetUSBNative(JNIEnv *env, jobject thiz, jint fd) {
    int ret = ioctl(fd, USBDEVFS_RESET);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_de_moleman1024_audiowagon_filestorage_usb_lowlevel_JavaAndroidUSBCommunication_clearHaltNative(JNIEnv *env, jobject thiz, jint fd, jint endpoint) {
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &endpoint);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

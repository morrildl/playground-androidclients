LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := warden-signapk
LOCAL_SRC_FILES := $(call all-java-files-under, java/src)
LOCAL_JAR_MANIFEST := java/Warden.mf
$(call dist-for-goals,dist_files,$(LOCAL_BUILT_MODULE))
include $(BUILD_HOST_JAVA_LIBRARY)

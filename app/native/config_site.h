/* Copied to pjlib/include/pj/config_site.h by native/build.sh */
#ifndef __PJ_CONFIG_SITE_H__
#define __PJ_CONFIG_SITE_H__

/* We define JNI_OnLoad ourselves in jiosip.cpp (it hands the JavaVM to pjlib). */
#define PJ_JNI_HAS_JNI_ONLOAD               0

/* Audio: pjmedia's AudioRecord/AudioTrack backend. It records with the VOICE_COMMUNICATION
 * source (hardware echo cancel / noise suppression) and plays on STREAM_VOICE_CALL, so the
 * earpiece / speakerphone routing done with AudioManager in the app just works. */
#define PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI   1
#define PJMEDIA_AUDIO_DEV_HAS_OPENSL        0
#define PJMEDIA_AUDIO_DEV_HAS_OBOE          0

/* A software clock gives more regular RTP timing on phones */
#define PJSUA_DEFAULT_SND_USE_SW_CLOCK      PJ_TRUE

#define PJMEDIA_HAS_VIDEO                   0
#define PJMEDIA_HAS_L16_CODEC               0
#define PJSUA_MAX_CALLS                     8

#endif

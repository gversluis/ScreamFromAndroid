# ScreamFromAndroid

**Stream audio from your Android device to a Scream receiver.**  

ScreamFromAndroid turns your phone into a network audio source which sends your audio to Scream audio receivers on your local network.
I wrote this for my own use so it is not tested except for my current Android.

---
## 📌 Features

- 📡 **Stream Android audio to a Scream receiver** on the network (IP or broadcast)
- 🎧 Choose **mono or stereo** audio to optimize bandwidth
- 🔇 **Optionally mute local device playback** so sound only plays on the receiver
- 🔊 Adjust **streaming volume** using Android’s volume bar and buttons
- 📞 Audio from **your calls are not streamed**
- 🛡️ **Your data is yours**, no ads, no external monitoring, no measurements
- 🤝 Open source **LGPL license** you can modify the code for your fittings

---

## 📷 Screenshots

<img src="/screenshots/config.png" height="600" alt="Screenshot of configuration" /> <img src="/screenshots/streaming.png" height="600" alt="Screenshot of streaming" />

---
## 🏛️ Background

My girlfriend was playing a part on the piano. I wanted to let her hear the original. Usually I did streaming with bluetooth which started stuttering every time something was between me and the receiver.

I recently setup Scream from my desktop. Maybe there would also be an Android client.

I couldn't find an existing Android Scream Client and asked AI (sorry). Which made a non-working mess. But it was a good start and the protocol is not that complex (for humans).

So I fixed the code and had a POC. In the meantime I made a few improvements and here is the result.

---
## 📋 Requirements

- Android 10+
- Permissions
  - RECORD_AUDIO for recording media session
  - INTERNET for casting over the network
  - FOREGROUND_SERVICE to run in the background
  - POST_NOTIFICATIONS to run in the background and make it visible that you are streaming
- Scream receiver

---
## 🚀 Getting Started

1. Setup your [Scream receiver](https://github.com/duncanthrax/scream)
2. Download the APK from the [Releases](https://github.com/gversluis/ScreamFromAndroid/releases)
3. Grant permissions before streaming
4. Configure app, default values are predefined so it should be a click and go for a default setup
5. Click "Start streaming"
6. When asked to share your screen pick "Share one app" or "Share entire screen". It does not seem to matter which app, audio is always streamed.
7. To stop streaming swipe the notification away, or open the app or tap the notification and click "Stop streaming"

---
## 🔊 Scream receivers

Source: [https://github.com/duncanthrax/scream]

- [Scream](https://github.com/martinellimarco/scream-android/), Android receiver
- [Scream](https://github.com/duncanthrax/scream/tree/master/Receivers/unix) *nix receiver for Redhat, CentOS, Debian, Ubuntu, etc. (Alsa/PulseAudio/Jack)
- [Windows: ScreamReader](https://github.com/duncanthrax/scream/releases) by @MrShoenel, Windows receiver included in the installer package as of version 1.2
- [Cornrow](https://github.com/mincequi/cornrow) Linux audio sink daemon
- [STM32F429 Scream receiver](https://tomeko.net/projects/scream_eth/) Embedded stream receiver. You might need to change the source code so it accepts 44100Hz audio
- [ESP32 Scream receiver](https://tomeko.net/projects/esp32_rtp_pager/) Embedded stream receiver. You might need to change the source code so it accepts 44100Hz audio

---
## 🚨 Troubleshooting

I don't hear sound
- Make sure your Scream receiver is running
- Ensure there is an app on your phone which makes sound
- Try to directly stream to the IP of the receiver
- Check the port of the receiver (default is 4010)
- Ensure your Android device and Scream receiver are on the same local network
- Ensure your router is not blocking internal traffic
- Ensure casting volume is set after you start streaming

I keep ending up in my App info
- Go to permissions and grant all the permissions

The sound stutters
- On the starting screen select "Mono"
- Try to directly stream to the device
- Stand somewhere where your Android phone has good wifi reception
- Select a different radio channel on your modem
- Modify the source code and try different package size

---

## 📄 Credits

- Author: Gerben Versluis
- Distributed under the terms specified in the repository [LICENSE](LICENSE).


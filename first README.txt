ClassroomLink

An open-source Android app for remote classroom device management based on MQTT.

📌 Overview

ClassroomLink is designed for teachers and classroom administrators. It connects to classroom PCs via the MQTT protocol, enabling real-time messaging and remote control from an Android phone. Teachers can send text and image messages, remotely shut down or restart PCs, open applications or files, view logs, and broadcast to multiple classes simultaneously.

This project is suitable for smart classrooms, computer lab management, and remote teaching scenarios.

✨ Features

· 📨 Messaging: Send text and image messages to classroom PCs.
· 🖥️ Remote Control: Shut down, restart, open apps, or open files on the PC.
· 📋 Log Viewer: Check the PC's activity logs directly from your phone.
· 📢 Multi-Class Broadcast: Send messages to multiple classrooms at once.
· ⚡ Quick Replies: Save common messages for fast sending.
· 🪟 Floating Notifications: Show messages in a floating window over other apps.
· 🌙 Auto Dark Mode: Automatically switches based on the time of day.

---

🛠️ Prerequisites

Before using this app, you will need:

1. An EMQX Cloud account (or any other MQTT Broker).
2. A PC running the companion receiver script/program (See the PC-Server folder for details).
3. An Android device (Android 5.0+).

(Note: Because this is a native Java/Android application, it currently does not support iOS/iPhone. iPhone users can only view messages by using an MQTT web client.)

---

📥 Installation Guide

Android Installation

1. Go to the Releases section of this GitHub repository.
2. Download the latest ClassroomLink.apk file to your phone.
3. On your phone, go to Settings → Security → Special App Access → Install unknown apps, and allow your browser or file manager to install unknown apps.
4. Open the downloaded .apk file and click Install.

iPhone Installation

Currently, there is no iOS version available. The APK file cannot be installed on an iPhone. If you are an iPhone user, you must use the web-based MQTT dashboard provided by EMQX to send and receive messages.

PC Setup (Receiver)

1. Download the PC receiver script from the PC-Server directory of this repository.
2. Install Python or Node.js (depending on the script).
3. Run the script on your classroom PC. Make sure the PC and your phone are connected to the same MQTT broker.

---

🚀 How to Use

Step 1: Configure Your EMQX Credentials

Because this project is open-source, the developer's private credentials have been removed. You must use your own EMQX Cloud account.

1. Open MainActivity.java in Android Studio or AIDE.
2. Press Ctrl + F (or search) and find:
   YOUR_EMQX_HOST
   YOUR_EMQX_USERNAME
   YOUR_EMQX_PASSWORD
3. Replace these with the Broker Address, Username, and Password from your EMQX Cloud deployment.
4. Compile the APK.

Step 2: Initial App Setup

1. Open the app on your Android phone.
2. Enter your name (e.g., "Mr. Smith").
3. Enter a Channel Name (e.g., "Class101"). This acts as a unique "room" for your phone and PC to communicate.
4. Tap Enter. The app will automatically connect to the MQTT server. You should see "Connected" at the top of the screen.

Step 3: PC Connection

1. On your classroom PC, open the companion receiver program.
2. Configure the PC script with the exact same EMQX credentials and Channel Name ("Class101").
3. Once the PC connects, the app will show "PC Connected" in green.

Step 4: Daily Use

· Send Messages: Type in the text box and click "Send". You can also attach up to 6 images.
· Remote Control: Click "Open Apps", "View Logs", or "Power" to send commands to the PC.
· Multi-Class Broadcast: Click "Broadcast", select multiple classes, and send a message to all of them at once.

---

🤝 Contributing

Contributions are welcome! If you want to improve this project:

1. Fork this repository.
2. Create a new branch (git checkout -b feature/AmazingFeature).
3. Commit your changes (git commit -m 'Add some AmazingFeature').
4. Push to the branch (git push origin feature/AmazingFeature).
5. Open a Pull Request.

📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

📞 Contact

If you have any questions or feedback, please open an Issue in the GitHub repository.
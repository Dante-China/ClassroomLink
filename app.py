import sys, os, uuid, threading, queue, base64, ssl, json, logging, subprocess, time
import concurrent.futures
import asyncio
import tempfile
from PIL import Image, ImageTk
import pystray
import tkinter as tk
from tkinter import filedialog, messagebox
import tkinter.simpledialog as simpledialog
import paho.mqtt.client as mqtt
import winreg

# ==================== 配置区域 ====================
BROKER = "o11797c9.ala.cn-shenzhen.emqxsl.cn"
PORT = 8883
USERNAME = "dante"
PASSWORD = "123456"
TOPIC_PREFIX = "classroom/msg/"
HEARTBEAT_TOPIC = "classroom/heartbeat"
HEARTBEAT_REQUEST_TOPIC = "classroom/heartbeat_request"
PHONE_HEARTBEAT_TOPIC_PREFIX = "classroom/phone_heartbeat/"
PHONE_HEARTBEAT_TOPIC = ""
HEARTBEAT_INTERVAL = 5
TOPIC = ""
# ==================================================

if getattr(sys, 'frozen', False):
    BASE_DIR = os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

CONFIG_FILE = os.path.join(BASE_DIR, 'config.txt')
LOG_FILE = os.path.join(BASE_DIR, 'log.txt')
HISTORY_FILE = os.path.join(BASE_DIR, 'history.json')

logger = logging.getLogger()
logger.setLevel(logging.INFO)
fh = logging.FileHandler(LOG_FILE, mode='w', encoding='utf-8')
fh.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(message)s'))
logger.addHandler(fh)

UPLOAD_FOLDER = os.path.join(BASE_DIR, 'uploads')
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

msg_queue = queue.Queue()
tray_icon = None
mqtt_client = None
user_channel_name = ""
kicked_flag = [False]
history_lock = threading.Lock()

# 手机心跳状态
phone_heartbeat_lock = threading.Lock()
phone_heartbeat_last = [0]
phone_heartbeat_name = [""]

# ========= 日志尾部 =========
def get_log_tail(n=30):
    if not os.path.exists(LOG_FILE):
        return "日志文件不存在"
    try:
        with open(LOG_FILE, 'rb') as f:
            f.seek(0, 2)
            size = f.tell()
            block = 4096
            data = b''
            while size > 0 and data.count(b'\n') <= n:
                read = min(block, size)
                size -= read
                f.seek(size)
                data = f.read(read) + data
            lines = data.decode('utf-8', errors='replace').splitlines()
            return '\n'.join(lines[-n:])
    except Exception as e:
        return f"读取日志失败: {e}"

# ========= 消息历史 =========
def add_history(entry):
    try:
        with history_lock:
            data = []
            if os.path.exists(HISTORY_FILE):
                try:
                    with open(HISTORY_FILE, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                except Exception:
                    data = []
            data.append(entry)
            if len(data) > 200:
                data = data[-200:]
            with open(HISTORY_FILE, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logging.error(f"保存历史失败: {e}")

# ========= 发送最后一次日志 =========
def send_last_log():
    global mqtt_client
    try:
        if mqtt_client and mqtt_client.is_connected():
            content = get_log_tail(30)
            result = mqtt_client.publish(TOPIC, 'CMD:LAST_LOG:' + content)
            result.wait_for_publish(timeout=2)
            logging.info("已发送最后一次日志")
    except Exception as e:
        logging.error(f"发送最后一次日志失败: {e}")

# ========= 朗读文字 =========
def speak_text(text, voice='zh-CN-XiaoxiaoNeural'):
    if not text:
        return
    if not voice:
        voice = 'zh-CN-XiaoxiaoNeural'
    def _speak():
        try:
            import edge_tts
            from pygame import mixer
            tmp_mp3 = os.path.join(tempfile.gettempdir(), 'classroom_tts.mp3')
            async def _gen():
                communicate = edge_tts.Communicate(text, voice)
                await communicate.save(tmp_mp3)
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    new_loop = asyncio.new_event_loop()
                    asyncio.set_event_loop(new_loop)
                    new_loop.run_until_complete(_gen())
                    new_loop.close()
                else:
                    loop.run_until_complete(_gen())
            except RuntimeError:
                asyncio.run(_gen())
            mixer.init()
            mixer.music.load(tmp_mp3)
            mixer.music.play()
            while mixer.music.get_busy():
                time.sleep(0.1)
            mixer.music.stop()
            mixer.quit()
            logging.info(f"朗读完成: {text[:30]}")
        except Exception as e:
            logging.error(f"朗读失败: {e}")
    threading.Thread(target=_speak, daemon=True).start()

# ========= 被踢出提示 =========
def show_kicked_message():
    if kicked_flag[0]:
        return
    kicked_flag[0] = True
    def _show():
        try:
            kwin = tk.Toplevel(root)
            kwin.attributes('-fullscreen', True)
            kwin.attributes('-topmost', True)
            kwin.configure(bg='black')
            kwin.overrideredirect(True)
            kwin.geometry(f"{kwin.winfo_screenwidth()}x{kwin.winfo_screenheight()}+0+0")
            sw = kwin.winfo_screenwidth()
            sh = kwin.winfo_screenheight()
            tk.Label(kwin, text='⚠️', font=('Microsoft YaHei', int(sh * 0.15)), fg='#f44336', bg='black').place(relx=0.5, rely=0.35, anchor='center')
            tk.Label(kwin, text='您已被管理员踢出', font=('Microsoft YaHei', int(sh * 0.05), 'bold'), fg='#f44336', bg='black').place(relx=0.5, rely=0.55, anchor='center')
            tk.Label(kwin, text='程序即将退出...', font=('Microsoft YaHei', int(sh * 0.025)), fg='#888888', bg='black').place(relx=0.5, rely=0.65, anchor='center')
            kwin.after(1500, lambda: os._exit(0))
        except Exception:
            time.sleep(1.5)
            os._exit(0)
    root.after(0, _show)

# ========= 消息历史窗口 =========
def open_history_window():
    def _show():
        win = tk.Toplevel(root)
        win.title("消息历史")
        win.configure(bg='#f5f7fa')
        w, h = 600, 680
        sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
        win.geometry(f"{w}x{h}+{(sw-w)//2}+{(sh-h)//2}")

        tk.Label(win, text="📜 消息历史", font=('Microsoft YaHei', 18, 'bold'),
                 fg='#00a884', bg='#f5f7fa').pack(pady=(16, 10))

        list_frame = tk.Frame(win, bg='#fff')
        list_frame.pack(fill='both', expand=True, padx=20, pady=(0, 10))

        scrollbar = tk.Scrollbar(list_frame)
        scrollbar.pack(side='right', fill='y')

        text_area = tk.Text(list_frame, font=('Microsoft YaHei', 11), wrap='word',
                            bg='#fff', fg='#1a2b3c', bd=0, padx=12, pady=12,
                            yscrollcommand=scrollbar.set)
        text_area.pack(fill='both', expand=True)
        scrollbar.config(command=text_area.yview)

        try:
            if os.path.exists(HISTORY_FILE):
                with open(HISTORY_FILE, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                if not data:
                    text_area.insert('end', '暂无历史消息\n')
                else:
                    for item in data:
                        t = item.get('time', '')
                        s = item.get('sender', '未知')
                        ty = item.get('type', 'text')
                        c = item.get('content', '')
                        if ty == 'text':
                            line = f"[{t}] {s}老师：\n    {c}\n\n"
                        elif ty == 'image':
                            line = f"[{t}] {s}老师：\n    [图片消息]\n\n"
                        elif ty == 'mix':
                            line = f"[{t}] {s}老师：\n    {c}\n    [含图片]\n\n"
                        else:
                            line = f"[{t}] {s}：\n    {c}\n\n"
                        text_area.insert('end', line)
            else:
                text_area.insert('end', '暂无历史消息\n')
        except Exception as e:
            text_area.insert('end', f'读取失败: {e}\n')

        text_area.config(state='disabled')

        btn_frame = tk.Frame(win, bg='#f5f7fa')
        btn_frame.pack(fill='x', padx=20, pady=(0, 16))

        def clear_hist():
            if not messagebox.askyesno("确认", "确定要清空所有历史消息吗？"):
                return
            try:
                if os.path.exists(HISTORY_FILE):
                    os.remove(HISTORY_FILE)
                messagebox.showinfo("完成", "已清空")
                win.destroy()
            except Exception as e:
                messagebox.showerror("失败", str(e))

        tk.Button(btn_frame, text="🗑️ 清空历史", command=clear_hist,
                  bg='#ef5350', fg='#fff', font=('Microsoft YaHei', 11, 'bold'),
                  bd=0, padx=20, pady=8).pack(side='left')
        tk.Button(btn_frame, text="关闭", command=win.destroy,
                  bg='#eef2f5', fg='#556670', font=('Microsoft YaHei', 11, 'bold'),
                  bd=0, padx=20, pady=8).pack(side='right')

    root.after(0, _show)

# ========= 给老师发消息窗口 =========
def open_send_dialog():
    def _show():
        win = tk.Toplevel(root)
        win.title("给老师发消息")
        win.configure(bg='#f5f7fa')
        win.resizable(False, False)
        w, h = 520, 700
        sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
        win.geometry(f"{w}x{h}+{(sw-w)//2}+{(sh-h)//2}")

        tk.Label(win, text="📤 给老师发消息", font=('Microsoft YaHei', 18, 'bold'), fg='#2e7d32', bg='#f5f7fa').pack(pady=(16, 6))
        tk.Label(win, text="发送的内容会立即显示在老师手机顶部", font=('Microsoft YaHei', 11), fg='#7f8c8d', bg='#f5f7fa').pack()

        status_frame = tk.Frame(win, bg='#fff', bd=0, highlightthickness=1, highlightbackground='#e0e6ed')
        status_frame.pack(fill='x', padx=24, pady=12)

        cloud_status_label = tk.Label(status_frame, text="☁️ 云服务器：检测中...", font=('Microsoft YaHei', 12, 'bold'), fg='#e74c3c', bg='#fff', anchor='w')
        cloud_status_label.pack(fill='x', padx=16, pady=(10, 4))
        phone_status_label = tk.Label(status_frame, text="📱 老师手机：检测中...", font=('Microsoft YaHei', 12, 'bold'), fg='#e74c3c', bg='#fff', anchor='w')
        phone_status_label.pack(fill='x', padx=16, pady=(4, 4))
        read_status_label = tk.Label(status_frame, text="👀 阅读状态：暂无", font=('Microsoft YaHei', 12, 'bold'), fg='#7f8c8d', bg='#fff', anchor='w')
        read_status_label.pack(fill='x', padx=16, pady=(4, 10))

        last_read = [""]

        def update_status():
            if mqtt_client and mqtt_client.is_connected():
                cloud_status_label.config(text="☁️ 云服务器：已连接", fg='#2e7d32')
            else:
                cloud_status_label.config(text="☁️ 云服务器：未连接", fg='#e74c3c')

            now = time.time()
            with phone_heartbeat_lock:
                last = phone_heartbeat_last[0]
                name = phone_heartbeat_name[0]
            if last > 0 and (now - last) < 10 and name == user_channel_name:
                phone_status_label.config(text=f"📱 老师手机：已连接（{name}）", fg='#2e7d32')
            else:
                phone_status_label.config(text="📱 老师手机：未连接", fg='#e74c3c')

            if last_read[0]:
                read_status_label.config(text=f"👀 阅读状态：{last_read[0]}", fg='#2e7d32')
            else:
                read_status_label.config(text="👀 阅读状态：暂无", fg='#7f8c8d')

            if win.winfo_exists():
                win.after(1000, update_status)
        update_status()

        tk.Label(win, text="快捷回复：", font=('Microsoft YaHei', 12, 'bold'), bg='#f5f7fa', fg='#2c3e50', anchor='w').pack(fill='x', padx=24, pady=(4, 4))
        quick_frame = tk.Frame(win, bg='#f5f7fa')
        quick_frame.pack(fill='x', padx=24)

        def set_quick(text):
            msg_text.delete('1.0', 'end')
            msg_text.insert('1.0', text)

        quick_items = ["上课了，请同学们安静", "下课了", "有同学来办公室", "请到我办公室", "请查看今天的作业"]
        for q in quick_items:
            tk.Button(quick_frame, text=q, bg='#e3f2fd', fg='#1976D2',
                      font=('Microsoft YaHei', 9), bd=0, padx=8, pady=4,
                      command=lambda t=q: set_quick(t)).pack(side='left', padx=(0, 6))

        tk.Label(win, text="消息内容：", font=('Microsoft YaHei', 12, 'bold'), bg='#f5f7fa', fg='#2c3e50', anchor='w').pack(fill='x', padx=24, pady=(12, 4))
        text_frame = tk.Frame(win, bg='#fff', bd=0, highlightthickness=1, highlightbackground='#e0e6ed')
        text_frame.pack(fill='x', padx=24)
        msg_text = tk.Text(text_frame, height=5, font=('Microsoft YaHei', 12), bd=0, relief='flat', wrap='word', padx=10, pady=8)
        msg_text.pack(fill='both', expand=True)

        tk.Label(win, text="图片（可选）：", font=('Microsoft YaHei', 12, 'bold'), bg='#f5f7fa', fg='#2c3e50', anchor='w').pack(fill='x', padx=24, pady=(12, 4))
        img_frame = tk.Frame(win, bg='#fff', bd=0, highlightthickness=1, highlightbackground='#e0e6ed')
        img_frame.pack(fill='x', padx=24)
        selected_images = []
        img_listbox = tk.Listbox(img_frame, height=3, font=('Microsoft YaHei', 10), bd=0, relief='flat')
        img_listbox.pack(side='left', fill='both', expand=True, padx=6, pady=6)

        def add_images():
            files = filedialog.askopenfilenames(
                parent=win, title="选择图片",
                filetypes=[("图片文件", "*.png *.jpg *.jpeg *.gif *.bmp"), ("所有文件", "*.*")])
            for f in files:
                if len(selected_images) >= 6:
                    break
                selected_images.append(f)
                img_listbox.insert('end', os.path.basename(f))
        tk.Button(img_frame, text="选择图片", command=add_images, bg='#2196F3', fg='#fff',
                  font=('Microsoft YaHei', 10, 'bold'), bd=0, padx=10, pady=4).pack(side='right', padx=6, pady=6)

        btn_frame = tk.Frame(win, bg='#f5f7fa')
        btn_frame.pack(fill='x', padx=24, pady=(16, 20))
        status_msg = tk.Label(btn_frame, text="", font=('Microsoft YaHei', 10), fg='#2e7d32', bg='#f5f7fa')
        status_msg.pack(anchor='w', pady=(0, 8))

        def do_send():
            text_content = msg_text.get('1.0', 'end').strip()
            if not text_content and not selected_images:
                status_msg.config(text="⚠️ 请输入文字或选择图片", fg='#e74c3c')
                return
            if not (mqtt_client and mqtt_client.is_connected()):
                status_msg.config(text="❌ 云服务器未连接", fg='#e74c3c')
                return
            try:
                images_b64 = []
                for path in selected_images:
                    with open(path, 'rb') as f:
                        img_data = f.read()
                    images_b64.append(base64.b64encode(img_data).decode('ascii'))
                payload = {
                    'sender': user_channel_name,
                    'content': text_content,
                    'type': 'mix' if (text_content and images_b64) else ('image' if images_b64 else 'text'),
                    'images': images_b64,
                    'time': time.strftime('%Y-%m-%d %H:%M:%S')
                }
                to_phone_topic = TOPIC + '/toPhone'
                mqtt_client.publish(to_phone_topic, json.dumps(payload, ensure_ascii=False))
                logging.info(f"已发送消息给老师: {text_content[:30]}")
                status_msg.config(text="✅ 发送成功！", fg='#2e7d32')
                msg_text.delete('1.0', 'end')
                selected_images.clear()
                img_listbox.delete(0, 'end')
            except Exception as e:
                status_msg.config(text=f"❌ 发送失败：{e}", fg='#e74c3c')

        tk.Button(btn_frame, text="发送", command=do_send, bg='#4caf50', fg='#fff',
                  font=('Microsoft YaHei', 13, 'bold'), bd=0, padx=30, pady=10).pack(side='right')
        tk.Button(btn_frame, text="关闭", command=win.destroy, bg='#ecf0f1', fg='#555',
                  font=('Microsoft YaHei', 13, 'bold'), bd=0, padx=30, pady=10).pack(side='right', padx=(0, 10))

    root.after(0, _show)

# ========= 扫描已安装软件 =========
def scan_installed_apps():
    apps = []
    seen = set()
    exclude_keywords = [
        'microsoft visual c++', 'microsoft .net', 'microsoft edge',
        'update for', 'hotfix', 'security update', 'windows sdk',
        'windows driver', 'runtime', 'microsoft windows',
        'python ', 'java ', 'adobe flash', 'java(tm)',
        'nvidia', 'intel(r)', 'realtek', 'amd ',
        'windows app', 'windows 更新', 'kb', '补丁'
    ]
    reg_paths = [
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
        (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
    ]
    for hkey, subkey in reg_paths:
        try:
            key = winreg.OpenKey(hkey, subkey)
            count = winreg.QueryInfoKey(key)[0]
            for i in range(count):
                try:
                    subkey_name = winreg.EnumKey(key, i)
                    sub = winreg.OpenKey(key, subkey_name)
                    try:
                        sys_comp, _ = winreg.QueryValueEx(sub, "SystemComponent")
                        if sys_comp == 1:
                            continue
                    except:
                        pass
                    try:
                        name, _ = winreg.QueryValueEx(sub, "DisplayName")
                    except:
                        continue
                    name_lower = name.lower()
                    skip = False
                    for kw in exclude_keywords:
                        if kw in name_lower:
                            skip = True
                            break
                    if skip:
                        continue
                    try:
                        icon_path, _ = winreg.QueryValueEx(sub, "DisplayIcon")
                        exe_path = icon_path.split(",")[0].strip('"')
                    except:
                        exe_path = ""
                    if name and exe_path and os.path.exists(exe_path):
                        key_name = name.lower()
                        if key_name not in seen:
                            seen.add(key_name)
                            apps.append({"name": name, "path": exe_path})
                except:
                    continue
        except:
            continue
    try:
        start_menus = [
            os.path.join(os.environ['APPDATA'], 'Microsoft', 'Windows', 'Start Menu', 'Programs'),
            os.path.join(os.environ['PROGRAMDATA'], 'Microsoft', 'Windows', 'Start Menu', 'Programs'),
        ]
        for sm in start_menus:
            if not os.path.exists(sm):
                continue
            for root_, dirs, files in os.walk(sm):
                for f in files:
                    if f.endswith('.lnk'):
                        app_name = f[:-4]
                        skip = False
                        for kw in exclude_keywords:
                            if kw in app_name.lower():
                                skip = True
                                break
                        if skip:
                            continue
                        if app_name.lower() not in seen:
                            seen.add(app_name.lower())
                            apps.append({"name": app_name, "path": os.path.join(root_, f)})
    except:
        pass
    return apps

def handle_app_list_request():
    try:
        apps = scan_installed_apps()
        apps.sort(key=lambda x: x['name'].lower())
        if mqtt_client:
            mqtt_client.publish(TOPIC, 'CMD:APP_LIST:' + json.dumps(apps, ensure_ascii=False))
        logging.info(f"已发送 {len(apps)} 个软件到手机")
    except Exception as e:
        logging.error(f"扫描软件失败: {e}")

def open_app_by_path(path):
    try:
        os.startfile(path)
        logging.info(f"打开软件: {path}")
    except Exception as e:
        logging.error(f"打开软件失败: {e}")
        try:
            subprocess.Popen([path], shell=True)
        except Exception as e2:
            logging.error(f"备用打开软件失败: {e2}")

# ========= 扫描 WPS 云文档 =========
def find_wps_cloud_files():
    user_profile = os.environ.get('USERPROFILE', '')
    appdata_local = os.path.join(user_profile, 'AppData', 'Local')
    appdata_roaming = os.path.join(user_profile, 'AppData', 'Roaming')
    possible_dirs = [
        os.path.join(user_profile, 'WPS Cloud Files'),
        os.path.join(appdata_local, 'Kingsoft', 'WPS Cloud Files'),
        os.path.join(user_profile, 'Documents', 'WPS Cloud Files'),
        os.path.join(user_profile, 'Kingsoft', 'WPS Cloud Files'),
        os.path.join(appdata_roaming, 'kingsoft', 'office6', 'wpscloudsvr'),
        os.path.join(appdata_local, 'Kingsoft', 'WPS Office'),
        os.path.join(appdata_roaming, 'kingsoft', 'office6', 'backup'),
        os.path.join(appdata_roaming, 'kingsoft', 'office6'),
    ]
    files = []
    exts = ['.docx', '.doc', '.xlsx', '.xls', '.pptx', '.ppt', '.pdf', '.txt', '.wps', '.et', '.dps']
    seen_paths = set()
    for base in possible_dirs:
        if not os.path.exists(base):
            continue
        try:
            for root_, dirs, fs in os.walk(base):
                depth = root_.count(os.sep) - base.count(os.sep)
                if depth > 6:
                    dirs[:] = []
                    continue
                for f in fs:
                    fl = f.lower()
                    if any(fl.endswith(ext) for ext in exts):
                        full = os.path.join(root_, f)
                        key = full.lower()
                        if key in seen_paths:
                            continue
                        seen_paths.add(key)
                        try:
                            mtime = os.path.getmtime(full)
                            files.append({'name': f, 'path': full, 'mtime': mtime})
                        except:
                            pass
        except:
            pass
    files.sort(key=lambda x: x['mtime'], reverse=True)
    return files[:100]

def handle_wps_cloud_list():
    try:
        files = find_wps_cloud_files()
        if not files:
            if mqtt_client:
                mqtt_client.publish(TOPIC, 'CMD:WPS_CLOUD_EMPTY')
            return
        simple = [{'name': f['name'], 'path': f['path']} for f in files]
        if mqtt_client:
            mqtt_client.publish(TOPIC, 'CMD:WPS_CLOUD_LIST:' + json.dumps(simple, ensure_ascii=False))
    except Exception as e:
        logging.error(f"扫描 WPS 失败: {e}")

# ========= 文件搜索 =========
SKIP_DIRS = {
    'windows', 'program files', 'program files (x86)', 'programdata',
    'node_modules', '.git', '.svn', '__pycache__', '.cache', '.idea',
    '$recycle.bin', 'system volume information', 'recovery',
    'msocache', '$winreagent', 'temp', 'tmp', 'cache', 'caches',
    'winsxs', 'installer', 'package cache', 'drivers', 'assembly',
    'application data', 'nvidia', 'intel', 'amd',
    'windowsapps', 'microsoft', 'common files', 'internet explorer'
}

def search_one_dir(root_dir, filename, max_depth=8, timeout=8.0):
    matches = []
    base_depth = root_dir.count(os.sep)
    start = time.time()
    fn_lower = filename.lower()
    has_ext = '.' in fn_lower
    try:
        for root_, dirs, files in os.walk(root_dir):
            if time.time() - start > timeout:
                break
            depth = root_.count(os.sep) - base_depth
            if depth >= max_depth:
                dirs[:] = []
                continue
            new_dirs = []
            for d in dirs:
                dl = d.lower()
                if dl in SKIP_DIRS or d.startswith('$') or d.startswith('.'):
                    continue
                new_dirs.append(d)
            dirs[:] = new_dirs
            for file in files:
                fl = file.lower()
                if has_ext:
                    if fl == fn_lower:
                        matches.append(os.path.join(root_, file))
                else:
                    name_without_ext = os.path.splitext(fl)[0]
                    if name_without_ext == fn_lower:
                        matches.append(os.path.join(root_, file))
                if len(matches) >= 10:
                    return matches
    except:
        pass
    return matches

def dedupe_paths(paths):
    unique = []
    seen_paths = set()
    for p in paths:
        key = p.lower()
        if key not in seen_paths:
            seen_paths.add(key)
            unique.append(p)
    return unique

def find_files(filename):
    user_profile = os.environ.get('USERPROFILE', '')
    fast_dirs = [
        os.path.join(user_profile, 'Desktop'),
        os.path.join(user_profile, 'Documents'),
        os.path.join(user_profile, 'Downloads'),
        os.path.join(user_profile, 'WPS Cloud Files'),
        os.path.join(user_profile, 'AppData', 'Local', 'Kingsoft', 'WPS Cloud Files'),
    ]
    matches = []
    for d in fast_dirs:
        if os.path.exists(d):
            matches.extend(search_one_dir(d, filename, max_depth=8, timeout=3.0))
            if matches:
                return dedupe_paths(matches)
    all_drives = []
    for drive in ['C:\\', 'D:\\', 'E:\\', 'F:\\', 'G:\\']:
        if os.path.exists(drive):
            all_drives.append(drive)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(search_one_dir, d, filename, 8, 8.0): d for d in all_drives}
        try:
            for future in concurrent.futures.as_completed(futures, timeout=9.0):
                try:
                    matches.extend(future.result())
                    if len(matches) >= 10:
                        break
                except:
                    pass
        except concurrent.futures.TimeoutError:
            pass
    return dedupe_paths(matches)

def open_file_by_path(path):
    try:
        os.startfile(path)
        keyword = os.path.splitext(os.path.basename(path))[0]
        threading.Thread(target=force_window_topmost, args=(keyword,), daemon=True).start()
        logging.info(f"打开文件: {path}")
    except Exception as e:
        logging.error(f"打开失败: {e}")

def handle_file_request(filename):
    matches = find_files(filename)
    if len(matches) == 0:
        if mqtt_client:
            mqtt_client.publish(TOPIC, 'CMD:FILE_NOT_FOUND:' + filename)
    elif len(matches) == 1:
        open_file_by_path(matches[0])
    else:
        file_info = []
        for i, path in enumerate(matches):
            file_info.append({'index': i, 'name': os.path.basename(path), 'path': path})
        if mqtt_client:
            mqtt_client.publish(TOPIC, 'CMD:FILE_LIST:' + json.dumps(file_info, ensure_ascii=False))

def force_window_topmost(keyword):
    for _ in range(5):
        time.sleep(1)
        try:
            ps_command = f"(New-Object -ComObject WScript.Shell).AppActivate('{keyword}')"
            result = subprocess.run(['powershell', '-Command', ps_command],
                                     capture_output=True, text=True,
                                     creationflags=subprocess.CREATE_NO_WINDOW)
            if "True" in result.stdout:
                return
        except:
            pass

# ========= 心跳 =========
def send_heartbeat_once():
    global mqtt_client, user_channel_name
    try:
        if mqtt_client and mqtt_client.is_connected():
            data = {
                'id': user_channel_name,
                'name': user_channel_name,
                'status': 'online',
                'time': time.strftime('%Y-%m-%d %H:%M:%S')
            }
            mqtt_client.publish(HEARTBEAT_TOPIC, json.dumps(data, ensure_ascii=False))
    except Exception as e:
        logging.error(f"心跳失败: {e}")

def publish_heartbeat():
    while True:
        send_heartbeat_once()
        time.sleep(HEARTBEAT_INTERVAL)

# ========= MQTT 回调 =========
def on_connect(client, userdata, flags, rc):
    global PHONE_HEARTBEAT_TOPIC
    if rc == 0:
        client.subscribe(TOPIC)
        client.subscribe(HEARTBEAT_REQUEST_TOPIC)
        client.subscribe(TOPIC + '/read')
        if PHONE_HEARTBEAT_TOPIC:
            client.subscribe(PHONE_HEARTBEAT_TOPIC)
        update_tray_icon("blue")
        logging.info(f"成功连接，主题: {TOPIC}")
        threading.Thread(target=send_heartbeat_once, daemon=True).start()
    else:
        update_tray_icon("black")

def on_disconnect(client, userdata, rc):
    update_tray_icon("black")

def on_message(client, userdata, msg):
    topic = msg.topic
    payload = msg.payload.decode('utf-8', errors='ignore')

    if topic == HEARTBEAT_REQUEST_TOPIC:
        threading.Thread(target=send_heartbeat_once, daemon=True).start()
        return

    if PHONE_HEARTBEAT_TOPIC and topic == PHONE_HEARTBEAT_TOPIC:
        try:
            data = json.loads(payload)
            with phone_heartbeat_lock:
                phone_heartbeat_last[0] = time.time()
                phone_heartbeat_name[0] = data.get('name', '')
        except:
            pass
        return

    if topic == TOPIC + '/read':
        return

    if payload.startswith("TEXT:"):
        text = payload[5:]
        msg_queue.put({'type': 'text', 'content': text, 'auto_close': 0, 'sender': '', 'mode': 'fullscreen'})

    elif payload.startswith("IMAGE:"):
        try:
            img_bytes = base64.b64decode(payload[6:])
            path = os.path.join(UPLOAD_FOLDER, uuid.uuid4().hex + '.png')
            with open(path, 'wb') as f:
                f.write(img_bytes)
            msg_queue.put({'type': 'image', 'path': path, 'auto_close': 0, 'sender': '', 'mode': 'fullscreen'})
        except:
            pass

    elif payload.startswith("MIX:"):
        try:
            data = json.loads(payload[4:])
            text = (data.get('text', '') or '').strip()
            images_b64 = data.get('images', [])
            auto_close = data.get('auto_close', 0)
            sender = data.get('sender', '')
            mode = data.get('mode', 'fullscreen')
            read_aloud = data.get('read_aloud', False)
            voice = data.get('voice', 'zh-CN-XiaoxiaoNeural')

            if read_aloud and text:
                speak_text(text, voice)

            if not images_b64 and text:
                msg_queue.put({'type': 'text', 'content': text, 'auto_close': auto_close, 'sender': sender, 'mode': mode})
            elif images_b64 and not text:
                path = os.path.join(UPLOAD_FOLDER, uuid.uuid4().hex + '_0.png')
                with open(path, 'wb') as f:
                    f.write(base64.b64decode(images_b64[0]))
                msg_queue.put({'type': 'image', 'path': path, 'auto_close': auto_close, 'sender': sender, 'mode': mode})
            else:
                image_paths = []
                for i, img_b64 in enumerate(images_b64):
                    path = os.path.join(UPLOAD_FOLDER, f"{uuid.uuid4().hex}_{i}.png")
                    with open(path, 'wb') as f:
                        f.write(base64.b64decode(img_b64))
                    image_paths.append(path)
                msg_queue.put({'type': 'mix', 'text': text, 'paths': image_paths, 'auto_close': auto_close, 'sender': sender, 'mode': mode})
        except Exception as e:
            logging.error(f"MIX 解析错误: {e}")

    elif payload.startswith("CMD:"):
        cmd = payload[4:].strip()
        if cmd.startswith("OPEN_FILE:"):
            filename = cmd[10:].strip()
            threading.Thread(target=handle_file_request, args=(filename,), daemon=True).start()
        elif cmd.startswith("OPEN_FILE_FULL:"):
            path = cmd[15:].strip()
            threading.Thread(target=open_file_by_path, args=(path,), daemon=True).start()
        elif cmd.startswith("OPEN_APP:"):
            path = cmd[9:].strip()
            threading.Thread(target=open_app_by_path, args=(path,), daemon=True).start()
        elif cmd == "GET_APP_LIST":
            threading.Thread(target=handle_app_list_request, daemon=True).start()
        elif cmd == "GET_WPS_CLOUD_LIST":
            threading.Thread(target=handle_wps_cloud_list, daemon=True).start()
        elif cmd == "GET_LOG":
            log_content = get_log_tail(30)
            if mqtt_client:
                result = mqtt_client.publish(TOPIC, 'CMD:LOG_DATA:' + log_content)
                try:
                    result.wait_for_publish(timeout=3)
                except:
                    pass
        elif cmd == "SHUTDOWN":
            logging.warning("收到远程关机指令，10秒后关机...")
            try:
                subprocess.Popen(['shutdown', '/s', '/t', '10'], shell=True)
            except Exception as e:
                logging.error(f"关机失败: {e}")
        elif cmd == "RESTART":
            logging.warning("收到远程重启指令，10秒后重启...")
            try:
                subprocess.Popen(['shutdown', '/r', '/t', '10'], shell=True)
            except Exception as e:
                logging.error(f"重启失败: {e}")
        elif cmd == "KICK":
            logging.warning("收到管理员踢出指令...")
            send_last_log()
            show_kicked_message()

def run_mqtt():
    global mqtt_client
    while True:
        try:
            client = mqtt.Client()
            client.tls_set(cert_reqs=ssl.CERT_NONE)
            client.tls_insecure_set(True)
            client.username_pw_set(USERNAME, PASSWORD)
            client.on_connect = on_connect
            client.on_message = on_message
            client.on_disconnect = on_disconnect
            client.connect(BROKER, PORT, 60)
            mqtt_client = client
            client.loop_forever()
        except Exception as e:
            logging.error(f"连接异常: {e}")
            update_tray_icon("black")
            time.sleep(5)

# ========= 托盘图标 =========
def create_image(color):
    if color == "blue":
        return Image.new('RGB', (64, 64), (30, 120, 220))
    else:
        return Image.new('RGB', (64, 64), (0, 0, 0))

def update_tray_icon(color):
    global tray_icon
    if tray_icon:
        tray_icon.icon = create_image(color)

def on_quit(icon, item):
    send_last_log()
    time.sleep(1)
    icon.stop()
    root.quit()
    os._exit(0)

def on_send_to_teacher(icon, item):
    open_send_dialog()

def run_tray():
    global tray_icon
    menu = pystray.Menu(
        pystray.MenuItem('给老师发消息', on_send_to_teacher),
        pystray.MenuItem('查看消息历史', lambda icon, item: open_history_window()),
        pystray.MenuItem('退出', on_quit)
    )
    tray_icon = pystray.Icon('教室接收', create_image("black"), '教室接收端', menu)
    tray_icon.run()

# ========= Tk 弹窗 =========
root = tk.Tk()
root.withdraw()
current_win = [None]

def close_fullscreen():
    if current_win[0]:
        try:
            current_win[0].destroy()
        except:
            pass
        current_win[0] = None

def calculate_font_size(text, max_font=200, min_font=40):
    if not text:
        return max_font
    length = len(text)
    if length <= 10:
        return max_font
    elif length <= 20:
        return 150
    elif length <= 50:
        return 100
    elif length <= 100:
        return 70
    elif length <= 200:
        return 50
    else:
        return min_font

def show_message(msg):
    close_fullscreen()

    # 保存历史
    sender = msg.get('sender', '')
    try:
        if msg['type'] == 'text':
            add_history({'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'sender': sender,
                         'type': 'text', 'content': msg.get('content', '')})
        elif msg['type'] == 'image':
            add_history({'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'sender': sender,
                         'type': 'image', 'content': ''})
        elif msg['type'] == 'mix':
            add_history({'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'sender': sender,
                         'type': 'mix', 'content': msg.get('text', '')})
    except Exception as e:
        logging.error(f"写历史失败: {e}")

    mode = msg.get('mode', 'fullscreen')
    auto_close = msg.get('auto_close', 0)

    if mode == 'mini':
        win = tk.Toplevel(root)
        win.overrideredirect(True)
        win.attributes('-topmost', True)
        win.geometry("400x200+50+50")
        win.configure(bg='#1e1e1e')
        win.bind('<Button-1>', lambda e: win.destroy())
        current_win[0] = win
        if sender:
            tk.Label(win, text=f"{sender}老师：", font=('Microsoft YaHei', 14, 'bold'), fg='#4caf50', bg='#1e1e1e').pack(anchor='w', padx=10, pady=(10, 0))
        if msg['type'] == 'text':
            tk.Label(win, text=msg['content'], font=('Microsoft YaHei', 14), fg='white', bg='#1e1e1e', wraplength=380, justify='left').pack(anchor='w', padx=10, pady=5)
        elif msg['type'] == 'mix':
            short = msg['text'][:50] + ('...' if len(msg['text']) > 50 else '')
            tk.Label(win, text=short, font=('Microsoft YaHei', 12), fg='white', bg='#1e1e1e', wraplength=380, justify='left').pack(anchor='w', padx=10, pady=5)
        else:
            tk.Label(win, text='[图片消息]', font=('Microsoft YaHei', 12), fg='white', bg='#1e1e1e').pack(anchor='w', padx=10, pady=5)
        if auto_close > 0:
            win.after(auto_close * 1000, win.destroy)
        return

    win = tk.Toplevel(root)
    win.attributes('-fullscreen', True)
    win.attributes('-topmost', True)
    win.configure(bg='black')
    win.bind('<Escape>', lambda e: close_fullscreen())
    win.bind('<Button-1>', lambda e: close_fullscreen())
    current_win[0] = win
    win.update_idletasks()
    sw = win.winfo_screenwidth()
    sh = win.winfo_screenheight()

    if sender:
        tk.Label(win, text=f"{sender}老师", font=('Microsoft YaHei', int(sh * 0.04), 'bold'),
                 fg='#4caf50', bg='black').place(relx=0.5, y=int(sh * 0.02), anchor='n')

    if msg['type'] == 'text':
        text = msg['content']
        font_size = calculate_font_size(text, max_font=int(sh * 0.18), min_font=int(sh * 0.04))
        tk.Label(win, text=text, font=('Microsoft YaHei', font_size, 'bold'), fg='white', bg='black',
                 wraplength=int(sw * 0.9), justify='center').place(relx=0.5, rely=0.5, anchor='center')

    elif msg['type'] == 'image':
        try:
            img = Image.open(msg['path'])
            iw, ih = img.size
            ratio = min(sw / iw, sh / ih)
            img = img.resize((int(iw * ratio), int(ih * ratio)), Image.LANCZOS)
            photo = ImageTk.PhotoImage(img)
            lbl = tk.Label(win, image=photo, bg='black')
            lbl.image = photo
            lbl.place(relx=0.5, rely=0.5, anchor='center')
        except:
            close_fullscreen()

    elif msg['type'] == 'mix':
        container = tk.Frame(win, bg='black')
        container.place(relx=0, rely=0.12, relwidth=1, relheight=0.88)
        left_frame = tk.Frame(container, bg='black')
        left_frame.place(relx=0, rely=0, relwidth=0.5, relheight=1)
        right_frame = tk.Frame(container, bg='black')
        right_frame.place(relx=0.5, rely=0, relwidth=0.5, relheight=1)
        for i, path in enumerate(msg['paths'][:6]):
            try:
                img = Image.open(path)
                img.thumbnail((int(sw * 0.23), int(sh * 0.28)), Image.LANCZOS)
                photo = ImageTk.PhotoImage(img)
                lbl = tk.Label(left_frame, image=photo, bg='black')
                lbl.image = photo
                lbl.grid(row=i // 2, column=i % 2, padx=6, pady=6)
            except:
                pass
        if msg['text']:
            text = msg['text']
            font_size = calculate_font_size(text, max_font=int(sh * 0.06), min_font=int(sh * 0.02))
            tk.Label(right_frame, text=text, font=('Microsoft YaHei', font_size, 'bold'), fg='white', bg='black',
                     wraplength=int(sw * 0.45), justify='center').pack(expand=True, fill='both')

    if auto_close > 0:
        win.after(auto_close * 1000, close_fullscreen)

def poll_queue():
    try:
        while True:
            show_message(msg_queue.get_nowait())
    except queue.Empty:
        pass
    root.after(300, poll_queue)

# ========= 启动 =========
if __name__ == '__main__':
    if '--rename' in sys.argv:
        rename_root = tk.Tk()
        rename_root.withdraw()
        old_name = ""
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    old_name = f.read().strip()
            except UnicodeDecodeError:
                with open(CONFIG_FILE, 'r', encoding='gbk') as f:
                    old_name = f.read().strip()
        new_name = simpledialog.askstring("修改专属通道", f"当前通道：{old_name}\n请输入新的专属通道名称：", parent=rename_root)
        if new_name:
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                f.write(new_name.strip())
            messagebox.showinfo("修改成功", f"通道名称已修改为：{new_name.strip()}\n请重新启动程序生效。")
        rename_root.destroy()
        sys.exit(0)

    temp_root = tk.Tk()
    temp_root.withdraw()

    user_input = ""
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                user_input = f.read().strip()
        except UnicodeDecodeError:
            try:
                with open(CONFIG_FILE, 'r', encoding='gbk') as f:
                    user_input = f.read().strip()
                with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                    f.write(user_input)
            except:
                user_input = ""

    if not user_input:
        alert_text = (
            "⚠️ 【系统专属机密警告】⚠️\n\n"
            "本核心系统由『至佳中学校2025级2班 - 何同学』亲手打造，专属定制！\n\n"
            "未经本人许可，严禁任何人私自盗用、破解、篡改或抄袭！\n"
            "违者必究到底，绝不手软！\n\n"
            "————————————\n"
            "【使用说明】\n"
            "1. 点击【确定】后，首次使用需输入专属通道名称（例如：2025级2班）。\n"
            "2. 输入后即可启动，手机端输入相同名称即可对接。\n"
            "3. 如需关闭电脑端，请右键右下角托盘图标，选择【退出】。\n"
            "4. 右键托盘图标还可选择【给老师发消息】【查看消息历史】。\n\n"
            "【图标状态提示】\n"
            "🔵 蓝色图标 = 已连接，运行中\n"
            "⚫ 黑色图标 = 未连接，已离线\n\n"
            "【修改通道说明】\n"
            "如需更改专属通道名称，请联系2025级2班何同学，或直接联系2025届二班班主任陈老师。\n"
            "————————————\n\n"
            "点击【确定】开启专属通道。"
        )
        messagebox.showinfo("系统安全验证", alert_text)

        input_root = tk.Tk()
        input_root.withdraw()
        user_input = simpledialog.askstring("设置专属通道（首次设置后永久固定）", "请输入与手机端一致的专属名称（例如：2025级2班）：", parent=input_root)
        if not user_input:
            user_input = "2025级2班"
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            f.write(user_input)
        input_root.destroy()

    temp_root.destroy()
    user_channel_name = user_input
    TOPIC = TOPIC_PREFIX + user_input
    PHONE_HEARTBEAT_TOPIC = PHONE_HEARTBEAT_TOPIC_PREFIX + user_input
    logging.info(f"使用主题: {TOPIC}")

    threading.Thread(target=run_mqtt, daemon=True).start()
    threading.Thread(target=run_tray, daemon=True).start()
    threading.Thread(target=publish_heartbeat, daemon=True).start()
    poll_queue()
    root.mainloop()

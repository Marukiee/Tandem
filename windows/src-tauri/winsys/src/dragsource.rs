//! Windows: a drag of files of the system itself, started under the pointer, so files that came over from another computer with the
//! button still down are let go wherever that button comes up: in a folder, on the desktop, in a field of a website. A window of a few
//! pixels is put under the pointer and a press is played onto it; the press starts the drag of the shell (`SHDoDragDrop`) with the files,
//! and from then on the moves and the release that the other computer sends drive it, as a hand on the mouse would.

use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
use windows::Win32::System::Com::IDataObject;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Ole::{DROPEFFECT_COPY, DROPEFFECT_NONE, IDropSource, OleInitialize, OleUninitialize};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBD_EVENT_FLAGS, KEYBDINPUT, KEYEVENTF_KEYUP, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
    MOUSEINPUT, SendInput, VK_ESCAPE,
};
use windows::Win32::UI::Shell::Common::ITEMIDLIST;
use windows::Win32::UI::Shell::{ILCreateFromPathW, ILFindLastID, ILFree, SHCreateDataObject, SHDoDragDrop};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetCursorPos, HWND_TOPMOST, LWA_ALPHA, MSG, PM_REMOVE,
    PeekMessageW, RegisterClassW, SWP_NOACTIVATE, SWP_SHOWWINDOW, SetLayeredWindowAttributes, SetWindowPos, TranslateMessage, WM_LBUTTONDOWN,
    WNDCLASSW, WS_EX_LAYERED, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP,
};
use windows::core::{HSTRING, w};

/// A drag of the system is going on, started here.
static ACTIVE: AtomicBool = AtomicBool::new(false);
thread_local! {
    /// What the window under the pointer hands to the shell when it is pressed, and what came of it. The window, its messages and the
    /// drag all live on the one thread that started it.
    static PENDING: RefCell<Option<IDataObject>> = const { RefCell::new(None) };
    static DROPPED: Cell<bool> = const { Cell::new(false) };
}

/// How long the press has to reach the window before it is given up.
const PRESS_WAIT: Duration = Duration::from_millis(1500);
const SIZE: i32 = 8;

pub fn active() -> bool {
    ACTIVE.load(Ordering::Relaxed)
}

/// Starts the drag of `files` (all in one folder) where the pointer is. `done` hears whether they were dropped somewhere; when not, the
/// caller puts them away as before. False when the drag could not be started at all.
pub fn start(files: Vec<PathBuf>, done: impl FnOnce(bool) + Send + 'static) -> bool {
    if files.is_empty() || ACTIVE.swap(true, Ordering::SeqCst) {
        return false;
    }
    let started = std::thread::Builder::new()
        .name("tandem-drag-source".into())
        .spawn(move || {
            let dropped = unsafe { run(&files) };
            ACTIVE.store(false, Ordering::SeqCst);
            done(dropped);
        })
        .is_ok();
    if !started {
        ACTIVE.store(false, Ordering::SeqCst);
    }
    started
}

/// The button came up before the drag here had it: the press that was played is let go too, so nothing stays held.
pub fn release() {
    if active() {
        unsafe { click(MOUSEEVENTF_LEFTUP) };
    }
}

/// The pointer went back to the other computer with the button still down: the drag here is called off (Escape, then the button up),
/// so nothing is dropped where the pointer left this screen.
pub fn cancel() {
    if !active() {
        return;
    }
    let key = |flags| INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VK_ESCAPE, dwFlags: flags, ..Default::default() } },
    };
    unsafe { SendInput(&[key(KEYBD_EVENT_FLAGS(0)), key(KEYEVENTF_KEYUP)], std::mem::size_of::<INPUT>() as i32) };
    unsafe { click(MOUSEEVENTF_LEFTUP) };
}

unsafe fn click(flags: windows::Win32::UI::Input::KeyboardAndMouse::MOUSE_EVENT_FLAGS) {
    let input = INPUT { r#type: INPUT_MOUSE, Anonymous: INPUT_0 { mi: MOUSEINPUT { dwFlags: flags, ..Default::default() } } };
    unsafe { SendInput(&[input], std::mem::size_of::<INPUT>() as i32) };
}

/// The files as a data object of the shell, which every program that takes files understands (CF_HDROP and the shell's own formats).
unsafe fn data_object(files: &[PathBuf]) -> Option<IDataObject> {
    let folder = files[0].parent()?;
    let folder_id = unsafe { ILCreateFromPathW(&HSTRING::from(folder.as_os_str())) };
    if folder_id.is_null() {
        return None;
    }
    let full: Vec<*mut ITEMIDLIST> = files.iter().map(|f| unsafe { ILCreateFromPathW(&HSTRING::from(f.as_os_str())) }).filter(|p| !p.is_null()).collect();
    let children: Vec<*const ITEMIDLIST> = full.iter().map(|p| unsafe { ILFindLastID(*p) } as *const ITEMIDLIST).collect();
    let made = unsafe { SHCreateDataObject::<_, IDataObject>(Some(folder_id), Some(&children), None::<&IDataObject>) }.ok();
    for p in full {
        unsafe { ILFree(Some(p)) };
    }
    unsafe { ILFree(Some(folder_id)) };
    made
}

unsafe fn run(files: &[PathBuf]) -> bool {
    let _ = unsafe { OleInitialize(None) };
    let dropped = unsafe { drag(files) };
    unsafe { OleUninitialize() };
    dropped
}

unsafe fn drag(files: &[PathBuf]) -> bool {
    let Some(data) = (unsafe { data_object(files) }) else { return false };
    PENDING.with(|p| *p.borrow_mut() = Some(data));
    DROPPED.with(|d| d.set(false));
    let Ok(module) = (unsafe { GetModuleHandleW(None) }) else { return false };
    let class = w!("TandemDragSource");
    let window_class = WNDCLASSW { lpfnWndProc: Some(proc), hInstance: module.into(), lpszClassName: class, ..Default::default() };
    unsafe { RegisterClassW(&window_class) };
    let mut at = POINT::default();
    let _ = unsafe { GetCursorPos(&mut at) };
    let Ok(window) = (unsafe {
        CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_NOACTIVATE,
            class,
            w!(""),
            WS_POPUP,
            at.x - SIZE / 2,
            at.y - SIZE / 2,
            SIZE,
            SIZE,
            None,
            None,
            Some(module.into()),
            None,
        )
    }) else {
        PENDING.with(|p| p.borrow_mut().take());
        return false;
    };
    // Barely there to the eye, but a window that is fully see-through lets the press through to what is under it.
    let _ = unsafe { SetLayeredWindowAttributes(window, Default::default(), 1, LWA_ALPHA) };
    let _ = unsafe { SetWindowPos(window, Some(HWND_TOPMOST), at.x - SIZE / 2, at.y - SIZE / 2, SIZE, SIZE, SWP_NOACTIVATE | SWP_SHOWWINDOW) };
    unsafe { click(MOUSEEVENTF_LEFTDOWN) };
    // The press arrives as a message; the drag runs inside it and the message comes back when the drag is over.
    let deadline = Instant::now() + PRESS_WAIT;
    let mut msg = MSG::default();
    while PENDING.with(|p| p.borrow().is_some()) && Instant::now() < deadline {
        while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
            let _ = unsafe { TranslateMessage(&msg) };
            unsafe { DispatchMessageW(&msg) };
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let missed = PENDING.with(|p| p.borrow_mut().take()).is_some();
    if missed {
        // The press went somewhere else: let go of it there, so nothing is dragged by accident.
        unsafe { click(MOUSEEVENTF_LEFTUP) };
        log::info!("drag source: the press did not reach the window under the pointer");
    }
    let _ = unsafe { DestroyWindow(window) };
    !missed && DROPPED.with(Cell::get)
}

unsafe extern "system" fn proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if message == WM_LBUTTONDOWN {
        let data = PENDING.with(|p| p.borrow().clone());
        if let Some(data) = data {
            // The window steps aside first: under the pointer it would be the place of the drop itself.
            let _ = unsafe { SetWindowPos(window, None, -32000, -32000, 0, 0, SWP_NOACTIVATE) };
            let effect = unsafe { SHDoDragDrop(Some(window), &data, None::<&IDropSource>, DROPEFFECT_COPY) };
            let dropped = effect.is_ok_and(|e| e != DROPEFFECT_NONE);
            log::info!("drag source: the drag ended, {}", if dropped { "dropped" } else { "not dropped" });
            DROPPED.with(|d| d.set(dropped));
            PENDING.with(|p| p.borrow_mut().take());
        }
        return LRESULT(0);
    }
    unsafe { DefWindowProcW(window, message, wparam, lparam) }
}

//! Hidden top-level window on its own thread: broadcast session and power messages reach it.
use crate::{engine_host::EngineHost, quit::QuitCoordinator};
use std::sync::Arc;
#[cfg(windows)]
mod windows {
    use super::*;
    use crate::{quit::QuitCause, supervisor::PowerEvent};
    use windows_sys::Win32::{
        Foundation::*,
        System::{
            LibraryLoader::GetModuleHandleW,
            Shutdown::{ShutdownBlockReasonCreate, ShutdownBlockReasonDestroy},
        },
        UI::WindowsAndMessaging::*,
    };
    struct Context {
        host: Arc<EngineHost>,
        quit: Arc<QuitCoordinator>,
    }
    pub struct SessionWindow {
        hwnd: usize,
        thread: Option<std::thread::JoinHandle<()>>,
    }
    impl Drop for SessionWindow {
        fn drop(&mut self) {
            unsafe {
                PostMessageW(self.hwnd as HWND, WM_CLOSE, 0, 0);
            }
            if let Some(thread) = self.thread.take() {
                let _ = thread.join();
            }
        }
    }
    unsafe extern "system" fn proc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        if msg == WM_NCCREATE {
            let create = &*(l as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
        }
        let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const Context;
        if !ptr.is_null() {
            let context = &*ptr;
            match msg {
                WM_QUERYENDSESSION => {
                    context.quit.begin(QuitCause::SessionEnd {
                        close_app: l as u32 & ENDSESSION_CLOSEAPP != 0,
                    });
                    let text: Vec<u16> = "Closing JustSearch safely\0".encode_utf16().collect();
                    ShutdownBlockReasonCreate(hwnd, text.as_ptr());
                    return 1;
                }
                WM_ENDSESSION => {
                    if w != 0 {
                        let ticket = context.quit.begin(QuitCause::SessionEnd {
                            close_app: l as u32 & ENDSESSION_CLOSEAPP != 0,
                        });
                        let _ = context.quit.wait(ticket);
                    }
                    ShutdownBlockReasonDestroy(hwnd);
                    return 0;
                }
                WM_POWERBROADCAST => {
                    match w as u32 {
                        PBT_APMSUSPEND => context.host.note_power(PowerEvent::Suspend),
                        PBT_APMRESUMEAUTOMATIC | PBT_APMRESUMESUSPEND => {
                            context.host.note_power(PowerEvent::Resume)
                        }
                        _ => {}
                    }
                    return 1;
                }
                WM_CLOSE => {
                    DestroyWindow(hwnd);
                    return 0;
                }
                WM_DESTROY => {
                    PostQuitMessage(0);
                    return 0;
                }
                _ => {}
            }
        }
        DefWindowProcW(hwnd, msg, w, l)
    }
    pub fn start(
        host: Arc<EngineHost>,
        quit: Arc<QuitCoordinator>,
    ) -> Result<SessionWindow, String> {
        let (sent, received) = std::sync::mpsc::sync_channel(1);
        let thread = std::thread::spawn(move || unsafe {
            let context = Box::new(Context { host, quit });
            let class: Vec<u16> = "JustSearchSessionWindow\0".encode_utf16().collect();
            let instance = GetModuleHandleW(std::ptr::null());
            let mut wc: WNDCLASSW = std::mem::zeroed();
            wc.lpfnWndProc = Some(proc);
            wc.lpszClassName = class.as_ptr();
            wc.hInstance = instance;
            if RegisterClassW(&wc) == 0 {
                let _ = sent.send(Err(format!("RegisterClassW:{}", GetLastError())));
                return;
            }
            let hwnd = CreateWindowExW(
                0,
                class.as_ptr(),
                class.as_ptr(),
                0,
                0,
                0,
                0,
                0,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                instance,
                &*context as *const Context as *const _,
            );
            if hwnd.is_null() {
                let _ = sent.send(Err(format!("CreateWindowExW:{}", GetLastError())));
                return;
            }
            let _ = sent.send(Ok(hwnd as usize));
            let mut message: MSG = std::mem::zeroed();
            while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            UnregisterClassW(class.as_ptr(), instance);
        });
        match received.recv().map_err(|e| e.to_string())? {
            Ok(hwnd) => Ok(SessionWindow {
                hwnd,
                thread: Some(thread),
            }),
            Err(error) => {
                let _ = thread.join();
                Err(error)
            }
        }
    }
}
#[cfg(windows)]
pub(crate) use windows::start;
#[cfg(not(windows))]
pub(crate) fn start(_host: Arc<EngineHost>, _quit: Arc<QuitCoordinator>) -> Result<(), String> {
    Ok(())
}

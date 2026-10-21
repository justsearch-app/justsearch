use windows_sys::Win32::UI::Shell::{
    Common::ITEMIDLIST, ILClone, ILFindLastID, ILFree, ILRemoveLastID,
};

// Keep the native reveal arguments together so tests inspect exactly what production dispatches.
// The caller must keep the absolute item PIDL valid throughout this synchronous call.
pub(super) unsafe fn with_item_ids(
    item: *const ITEMIDLIST,
    dispatch: impl FnOnce(*const ITEMIDLIST, u32, *const *const ITEMIDLIST) -> Result<(), String>,
) -> Result<(), String> {
    let parent = unsafe { ILClone(item) };
    if parent.is_null() {
        return Err("Could not allocate the parent shell item".into());
    }
    struct Parent(*mut ITEMIDLIST);
    impl Drop for Parent {
        fn drop(&mut self) {
            unsafe { ILFree(self.0) };
        }
    }
    let parent = Parent(parent);
    if unsafe { ILRemoveLastID(parent.0) } == 0 {
        return Err("Could not identify the parent shell item".into());
    }
    let child = unsafe { ILFindLastID(item) };
    let children = [child.cast_const()];
    dispatch(parent.0, children.len() as u32, children.as_ptr())
}

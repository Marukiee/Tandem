; The app makes these links for the devices it knows (sendwith.rs) and is the only one that cleans them up, so once it is gone nobody does.
; An update does not need them kept: the app writes them again at every start.
!macro NSIS_HOOK_POSTUNINSTALL
  Delete "$APPDATA\Microsoft\Windows\SendTo\* (Tandem).lnk"
!macroend

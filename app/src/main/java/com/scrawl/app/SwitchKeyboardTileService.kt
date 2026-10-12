package com.scrawl.app

import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager

class SwitchKeyboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}

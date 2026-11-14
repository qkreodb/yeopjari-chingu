package com.yeopjari.app

import android.app.Application

class YeopjariApp : Application() {
    lateinit var controller: CoachController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = CoachController(this).also { it.start() }
    }
}

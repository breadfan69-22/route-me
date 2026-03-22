package com.routeme.app.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class RouteMeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR   // personal-use sideload — no host restriction

    override fun onCreateSession(): Session = RouteMeSession()
}

package com.hanhuy.android.keepshare

import org.acra.ACRA
import org.acra.annotation.ReportsCrashes

object Application {
  private var _instance: Application = _
  def instance = _instance
}
@ReportsCrashes(formKey = "",
  formUri = "http://hanhuy-acra.appspot.com/api/crashreport")
class Application extends android.app.Application {
  override def onCreate() {
    super.onCreate()
    ACRA.init(this)
    Application._instance = this
    PRNGFixes.apply()
  }
}

import android.Keys._

name	:= "test-client"

enablePlugins(AndroidApp)

platformTarget in Android		:= "android-19"

// necessary to allow our own java.rmi.* stubs
dexAdditionalParams	in Android	+= "--core-library"

proguardOptions in Android += "-ignorewarnings"

showSdkProgress in Android := false

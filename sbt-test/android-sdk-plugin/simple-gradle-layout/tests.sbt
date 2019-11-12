import android.Keys._
import android.BuildOutput._
import scala.xml._

TaskKey[Unit]("verify-package") := {
  val p = (applicationId in Android).value
  if (p != "com.example.app") sys.error("wrong package: " + p)
  ()
}

TaskKey[Unit]("verify-res-values") := {
  val p = (projectLayout in Android).value
  val o = (outputLayout in Android).value
  implicit val output = o
  val res = p.generatedRes / "values" / "generated.xml"
  val root = XML.loadFile(res)
  val node = root \ "string"
  if (node.isEmpty) sys.error("string node not found")
  val name = node.head.attribute("name").get.toString
  if ("test_resource" != name) sys.error(s"wrong name value: [$name] ${name.getClass}")
  val text = node.head.text
  if ("test value" != text) sys.error("wrong value: " + text)
  ()
}

TaskKey[Unit]("check-global-aar") := Def.task {
  val path = Path.userHome / ".android/sbt/exploded-aars/com.google.android.gms-play-services-4.3.23/com.google.android.gms-play-services-4.3.23.jar"
  if (!path.isFile) android.fail("path does not exist: " + path)
}

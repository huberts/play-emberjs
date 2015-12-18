package com.ketalo.play.plugins.emberjs

import java.io._
import com.typesafe.sbt.web.GeneralProblem
import org.apache.commons.io.FilenameUtils
import play.sbt.PlayExceptions

import sbt._
import org.slf4j.LoggerFactory

trait EmberJsTasks extends EmberJsKeys {
  val logger = LoggerFactory.getLogger("play-emberjs")

  val modificationTimeCache = scala.collection.mutable.Map.empty[String, Long] // Keeps track of the modification time

  val versions = Map(
    "1.0.0-pre.2" -> (("ember-1.0.0-pre.2.for-rhino", "handlebars-1.0.rc.1", "headless-ember-pre.2")),
    "1.0.0-rc.1"  -> (("ember-1.0.0-rc.1.for-rhino", "handlebars-1.0.rc.3", "headless-ember-rc.1")),
    "1.0.0-rc.3"  -> (("ember-1.0.0-rc.3.for-rhino", "handlebars-1.0.rc.3", "headless-ember-rc.1")),
    "1.0.0-rc.4"  -> (("ember-1.0.0-rc.4.for-rhino", "handlebars-1.0.rc.4", "headless-ember-rc.1")),
    "1.0.0-rc.5"  -> (("ember-1.0.0-rc.5.for-rhino", "handlebars-1.0.rc.4", "headless-ember-rc.1")),
    "1.0.0-rc.6"  -> (("ember-1.0.0-rc.6.for-rhino", "handlebars-1.0.rc.4", "headless-ember-rc.1")),
    "1.0.0-rc.7"  -> (("ember-1.0.0-rc.7.for-rhino", "handlebars-1.0.0", "headless-ember-rc.1")),
    "1.0.0-rc.8"  -> (("ember-1.0.0-rc.8.for-rhino", "handlebars-1.0.0", "headless-ember-rc.1")),
    "1.0.0"       -> (("ember-1.0.0.for-rhino", "handlebars-1.0.0", "headless-ember-1.0.0")),
    "1.1.2"       -> (("ember-1.1.2.for-rhino", "handlebars-1.0.0", "headless-ember-1.1.2")),
    "1.2.0"       -> (("ember-1.2.0.for-rhino", "handlebars-v1.1.2", "headless-ember-1.1.2")),
    "1.3.0"       -> (("ember-1.3.0.for-rhino", "handlebars-v1.2.1", "headless-ember-1.1.2")),
    "1.4.0"       -> (("ember-1.4.0.for-rhino", "handlebars-v1.3.0", "headless-ember-1.1.2")),
    "1.5.1"       -> (("ember-1.5.1.for-rhino", "handlebars-v1.3.0", "headless-ember-1.1.2")),
    "1.6.1"       -> (("ember-1.6.1.for-rhino", "handlebars-v1.3.0", "headless-ember-1.1.2")),
    "1.7.0"       -> (("ember-1.7.0.for-rhino", "handlebars-v1.3.0", "headless-ember-1.1.2"))
  )

  private def loadResource(name: String): Option[Reader] = {
    Option(this.getClass.getClassLoader.getResource(name)).map(_.openConnection().getInputStream).map(s => new InputStreamReader(s))
  }

  def compile(version:String, name: String, source: String): Either[(String, Int, Int), String] = {
    logger.info(s"Compile handlebars template: $name with ember version $version")

    import org.mozilla.javascript._
    import org.mozilla.javascript.tools.shell._

    val (ember, handlebars, headless) = versions.getOrElse(version, ("", "", ""))
    val ctx = Context.enter
    ctx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_1_7)
    ctx.setOptimizationLevel(-1) // Needed to get around a 64K limit

    val global = new Global
    global.init(ctx)
    val scope = ctx.initStandardObjects(global)

    def loadScript(script: String) {
      // load handlebars
      val scriptFile = loadResource(s"$script.js").getOrElse(throw new Exception(s"Could not find $script"))

      try {
        ctx.evaluateReader(scope, scriptFile, script, 1, null)
      } catch {
        case e: Exception => e.printStackTrace()
      }
    }
    loadScript(handlebars)
    loadScript(headless)
    loadScript(ember)

    ScriptableObject.putProperty(scope, "rawSource", source.replace("\r", ""))

    try {
      Right(ctx.evaluateString(scope, "(Ember.Handlebars.precompile(rawSource).toString())", "EmberJsCompiler", 0, null).toString)
    } catch {
      case e: JavaScriptException => {
        Left(e.details(), e.lineNumber(), 0)
      }
      case e: org.mozilla.javascript.EcmaError => {
        Left(e.details(), e.lineNumber(), 0)
      }
    }
  }

  protected def templateName(sourceFile: String, assetsDir: String): String = {
    val sourceFileWithForwardSlashes = FilenameUtils.separatorsToUnix(sourceFile)
    val assetsDirWithForwardSlashes  = FilenameUtils.separatorsToUnix(assetsDir)
    FilenameUtils.removeExtension(
      sourceFileWithForwardSlashes.replace(assetsDirWithForwardSlashes + "/", "")
    )
  }

  import Keys._

  lazy val EmberJsCompiler = (sourceDirectory in Compile, resourceManaged in Compile, cacheDirectory, emberJsVersion, emberJsTemplateFile, emberJsFileRegexFrom, emberJsFileRegexTo, emberJsAssetsDir, emberJsAssetsGlob).map {
      (src, resources, cache, version, templateFile, fileReplaceRegexp, fileReplaceWith, assetsDir, files) =>
      val cacheFile = cache / "emberjs"
      val templatesDir = resources / "public" / "templates"
      val global = templatesDir / templateFile
//      val globalMinified = templatesDir / s"${FilenameUtils.removeExtension(templateFile)}.min.js"

      def naming(name: String) = name.replaceAll(fileReplaceRegexp, fileReplaceWith)

      val latestTimestamp = files.get.sortBy(f => FileInfo.lastModified(f).lastModified).reverse.map(f => FileInfo.lastModified(f)).headOption.getOrElse(FileInfo.lastModified(global))
      val currentInfos = files.get.map(f => f -> FileInfo.lastModified(f))
//      val allFiles = (currentInfos ++ Seq(global -> latestTimestamp, globalMinified -> latestTimestamp)).toMap
      val allFiles = (currentInfos ++ Seq(global -> latestTimestamp)).toMap

      val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)
      val previousGeneratedFiles = previousRelation._2s

      if (previousInfo != allFiles) {
        val output = new StringBuilder
        output ++= """(function() {
          var template = Ember.Handlebars.template,
              templates = Ember.TEMPLATES = Ember.TEMPLATES || {};
                 """

        val generated:Seq[(File, File)] = (files x relativeTo(assetsDir)).flatMap {
          case (sourceFile, name) =>
            val template = templateName(sourceFile.getPath, assetsDir.getPath)
            val jsSource = if (modificationTimeCache.get(sourceFile.getAbsolutePath).map(time => time != sourceFile.lastModified()).getOrElse(true)) {
              logger.info(s"Compiling $sourceFile not in cache")
              compile(version, template, IO.read(sourceFile)).left.map {
                case (msg, line, column) => throw PlayExceptions.CompilationException(new GeneralProblem(msg, sourceFile))
              }.right.get
            } else {
              logger.info(s"Reading $name from the cache")
              IO.read(new File(resources, "public/templates/" + naming(name)))
            }
            modificationTimeCache += (sourceFile.getAbsolutePath -> sourceFile.lastModified)

            output ++= s"\ntemplates['$template'] = template($jsSource);\n\n"

            val out = new File(resources, "public/templates/" + naming(name))
            IO.write(out, jsSource)
            Seq(sourceFile -> out)
        }

        output ++= "})();\n"
        IO.write(global, output.toString())

        // Minify
//        val minified = play.core.jscompile.JavascriptCompiler.minify(output.toString, None)
//        IO.write(globalMinified, minified)

        val allTemplates = generated ++ Seq(global -> global)
//        val allTemplates = generated ++ Seq(global -> global, globalMinified -> globalMinified)

        Sync.writeInfo(cacheFile,
          Relation.empty[java.io.File, java.io.File] ++ allTemplates,
          allFiles)(FileInfo.lastModified.format)

        allTemplates.map(_._2).distinct.toSeq
      } else {
        previousGeneratedFiles.toSeq
      }
  }

}

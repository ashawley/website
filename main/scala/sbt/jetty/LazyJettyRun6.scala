package sbt.jetty

import java.io.File
import java.net.URL

/* This class starts Jetty.
* NOTE: DO NOT actively use this class.  You will see NoClassDefFoundErrors if you fail
*  to do so.Only use its name in JettyRun for reflective loading.  This allows using
*  the Jetty libraries provided on the project classpath instead of requiring them to be
*  available on sbt's classpath at startup.
*/
private object LazyJettyRun6 extends JettyRun
{
	
	import org.mortbay.jetty.{Handler, Server}
	import org.mortbay.jetty.nio.SelectChannelConnector
	import org.mortbay.jetty.webapp.{WebAppClassLoader, WebAppContext}
	import org.mortbay.log.{Log, Logger => JLogger}
	import org.mortbay.util.Scanner
	import org.mortbay.xml.XmlConfiguration

	import java.lang.ref.{Reference, WeakReference}

	val DefaultMaxIdleTime = 30000

	def apply(configuration: JettyConfiguration, jettyLoader: ClassLoader): Stoppable =
	{
		val oldLog = Log.getLog
		Log.setLog(new JettyLogger(configuration.log))
		val server = new Server

		def configureScanner(listener: Scanner.BulkListener, scanDirectories: Seq[File], scanInterval: Int) =
		{
			if(scanDirectories.isEmpty)
				None
			else
			{
				configuration.log.debug("Scanning for changes to: " + scanDirectories.mkString(", "))
				val scanner = new Scanner
				val list = new java.util.ArrayList[File]
				scanDirectories.foreach(x => list.add(x))
				scanner.setScanDirs(list)
				scanner.setRecursive(true)
				scanner.setScanInterval(scanInterval)
				scanner.setReportExistingFilesOnStartup(false)
				scanner.addListener(listener)
				scanner.start()
				Some(new WeakReference(scanner))
			}
		}

		val (listener, scanner) =
			configuration match
			{
				case c: DefaultJettyConfiguration =>
					import c._
					configureDefaultConnector(server, port)
					def classpathURLs = classpath.get.map(_.asURL).toSeq
					val webapp = new WebAppContext(war.absolutePath, contextPath)
					
					def createLoader =
					{
						class SbtWebAppLoader extends WebAppClassLoader(jettyLoader, webapp) { override def addURL(u: URL) = super.addURL(u) };
						val loader = new SbtWebAppLoader
						classpathURLs.foreach(loader.addURL)
						loader
					}
					def setLoader() = webapp.setClassLoader(createLoader)
					
					setLoader()
					server.setHandler(webapp)

					val listener = new Scanner.BulkListener with Reload {
						def reloadApp() = reload(server, setLoader(), log)
						def filesChanged(files: java.util.List[_]) { reloadApp() }
					}
					(Some(listener), configureScanner(listener, c.scanDirectories, c.scanInterval))
				case c: CustomJettyConfiguration =>
					for(x <- c.jettyConfigurationXML)
						(new XmlConfiguration(x.toString)).configure(server)
					for(file <- c.jettyConfigurationFiles)
						(new XmlConfiguration(file.toURI.toURL)).configure(server)
					(None, None)
			}

		try
		{
			server.start()
			new StopServer(new WeakReference(server), listener.map(new WeakReference(_)), scanner, oldLog)
		}
		catch { case e => server.stop(); throw e }
	}
	private def configureDefaultConnector(server: Server, port: Int)
	{
		val defaultConnector = new SelectChannelConnector
		defaultConnector.setPort(port)
		defaultConnector.setMaxIdleTime(DefaultMaxIdleTime)
		server.addConnector(defaultConnector)
	}
	trait Reload { def reloadApp(): Unit }
	private class StopServer(serverReference: Reference[Server], reloadReference: Option[Reference[Reload]], scannerReferenceOpt: Option[Reference[Scanner]], oldLog: JLogger) extends Stoppable
	{
		def reload(): Unit = on(reloadReference)(_.reloadApp())
		private def on[T](refOpt: Option[Reference[T]])(f: T => Unit): Unit = refOpt.foreach(ref => onReferenced(ref.get)(f))
		private def onReferenced[T](t: T)(f: T => Unit): Unit = if(t == null) () else f(t)
		def stop()
		{
			onReferenced(serverReference.get)(_.stop())
			on(scannerReferenceOpt)(_.stop())
			Log.setLog(oldLog)
		}
	}
	private def reload(server: Server, reconfigure: => Unit, log: Logger)
	{
		log.info("Reloading web application...")
		val handlers = wrapNull(server.getHandlers, server.getHandler)
		log.debug("Stopping handlers: " + handlers.mkString(", "))
		handlers.foreach(_.stop)
		log.debug("Reconfiguring...")
		reconfigure
		log.debug("Restarting handlers: " + handlers.mkString(", "))
		handlers.foreach(_.start)
		log.info("Reload complete.")
	}
	private def wrapNull(a: Array[Handler], b: Handler) =
		(a, b) match
		{
			case (null, null) => Nil
			case (null, notB) => notB :: Nil
			case (notA, null) => notA.toList
			case (notA, notB) => notB :: notA.toList
		}
	private class JettyLogger(delegate: Logger) extends JettyLoggerBase(delegate) with JLogger
	{
		def getLogger(name: String) = this
	}
}
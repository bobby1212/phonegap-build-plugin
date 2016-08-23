package es.gailen.jenkins.phonegapbuild

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.PUT
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.URLENC
import static groovyx.net.http.ContentType.HTML
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.ContentType.BINARY
import static groovyx.net.http.ContentType.JSON

import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.ByteArrayBody
import org.apache.http.entity.mime.content.StringBody

import hudson.FilePath
import hudson.util.DirScanner.Glob

import groovy.util.XmlSlurper
import groovy.util.XmlParser

//KEEP//@GrabResolver(name="repo.jenkins-ci.org",root='http://repo.jenkins-ci.org/public/')
//KEEP//@Grab(group='org.jenkins-ci.main', module='jenkins-core', version='1.580.1')
//KEEP//@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.1')
//KEEP//@Grab('org.apache.httpcomponents:httpmime:4.2.1')


class PhonegapBuilder {
  def token
  def appName = null
  def version = null
  def fileBaseName = null  // Basename for binaries
  def appId
  def androidKeyId
  def androidKeyPassword
  def androidKeystorePassword
  def iosKeyId
  def iosKeyPassword
  
  private Map appInfo
  private boolean overridedKeys = false
  private PrintStream logger = System.out

  private Map status = [android:'pending', ios:'pending']
  private List downloads = []
  private final String baseURL = 'https://build.phonegap.com'

  def http = new HTTPBuilder(baseURL)
  def slurper = new groovy.json.JsonSlurper()
  
  public PhonegapBuilder(String token, String appId, PrintStream logger) {
    this.logger = logger
    this.token = token
    this.appId = appId
    this.refreshAppInfo()
  }
  
  public PhonegapBuilder(String token, String appId, String androidKeyId, String iosKeyId, PrintStream logger = System.out) {
    this.logger = logger
    this.token = token
    this.appId = appId
    this.androidKeyId = androidKeyId
    this.iosKeyId = iosKeyId
    this.refreshAppInfo()
    this.overridedKeys = true
  }
  
  private void refreshAppInfo() {
    this.logger.println "Refreshing '${appId}' app info..."
    this.appInfo = http.get(path: "/api/v1/apps/${appId}", query: [auth_token: token])
  }

  void unlockKeys(androidKeyPass, androidKeystorePass, iosPass) {
    if (this.iosKeyId) {
        http.request(PUT) {
            uri.path = "/api/v1/keys/ios/${this.iosKeyId}?auth_token=${this.token}"
            send JSON, [
                auth_token: this.token,
                data: [ password: iosPass ]
            ]
            response.success = { resp, data ->
              this.logger.println "iOS key '${data.title}' (${this.iosKeyId}) locked: ${data.locked}"
            }
        }
    } else {
      logger.println("-- No iOS key to unlock --")
    }

    if (this.androidKeyId) {
      http.request(PUT) {
          uri.path = "/api/v1/keys/android/${this.androidKeyId}"
          send JSON, [
              auth_token: this.token,
              data: [
                  key_pw: androidKeyPass,
                  keystore_pw:androidKeystorePass
              ]
          ]
          response.success = { resp, data ->
            this.logger.println "Android key '${data.alias}' (${this.androidKeyId}) locked: ${data.locked}"

          }
      }
    } else {
      logger.println("-- No Android key to unlock --")
    }
  }
  
  List availablePlatformKeys(platform) {
    http.get(path:"/api/v1/keys/${platform}", query: [auth_token: token])?.keys
  }
  
  List availableAndroidKeys() {
    availablePlatformKeys('android')
  }

  List availableIosKeys() {
    availablePlatformKeys('ios')
  }
  
  private String prepareZipFilePath(FilePath workingDir) {
    Glob glob = new Glob('**/*.*', '**/.svn,node_modules/**,.git/**,plugins/**,platforms/**,*.ipa,*.apk,*.xap,bin/**,**/*.sh')
    File tmp = File.createTempFile("pgbuildtmp",".zip")
    tmp.deleteOnExit()
    workingDir.zip(tmp.newOutputStream(), glob)
    return tmp.absolutePath
  }
  
  private void uploadZipFile(String zippath) {
    this.logger.println "Uploading ${zippath}..."

    http.request PUT, TEXT, {req->
        uri.path =  "/api/v1/apps/${this.appId}?auth_token=${this.token}"
    
        MultipartEntity multipartRequestEntity = new MultipartEntity()
        multipartRequestEntity.addPart('file', new FileBody(new File(zippath), "text/txt"))
        multipartRequestEntity.addPart('auth_token', new StringBody(this.token))
        req.entity = multipartRequestEntity
        response.success = { resp, data ->
            def slurper = new groovy.json.JsonSlurper()
            def json = slurper.parseText(data.text)
            this.logger.println """
            App build launched:
              ID:\t\t${json.id}
              Title:\t${json.title}
              Package:\t${json.package}
              Version:\t${json.version}
              Builds:\t${json.build_count}
              Installers:\t${json.install_url}
              """.stripIndent()
        }
        
        response.failure = { resp, a ->
          this.logger.println "ERROR - ${resp.status}"
        }
    }
  }

  String extensions(platform) {
    [android:'apk', ios:'ipa', winphone:'xap'].get(platform)
  }

  private void waitForBinaries(workingDir) {
    this.logger.println "Waiting for Phonegap Build to sculpt binaries..."
    while ('pending' in [status.android, status.ios]) {
      this.logger.println "Asking for status..."
      sleep(10000)
      URL url = new URL("${baseURL}/api/v1/apps/?auth_token=${this.token}")
      def data = slurper.parseText(url.text)
      def app = data.apps.find { it.id.toString() == this.appId.toString() }
      if (!app) {
        this.logger.println "Application ${this.appId} not found!!!"
        System.exit(1)
      }
      status = app.status
      this.logger.println "Status: ${status}"
      status.each { platform, st ->
        if (st == 'complete' && !(platform in downloads)) {
            downloads << platform
            downloadPlatform(app, platform, this.fileBaseName, workingDir)
        }
      }
    }
  }

  private void downloadPlatform(app, platform, fileBaseName = "phonegapbuild", workingDir) {
      def slurper = new groovy.json.JsonSlurper()
      def androidURL = "${baseURL}${app.download[platform]}?auth_token=${this.token}"
      def androidFileURL = slurper.parseText(new URL(androidURL).text).location
      this.logger.println "Downloading ${extensions(platform)} binary from (${androidFileURL})"

      def file = new File("${workingDir}/${fileBaseName}-${this.version}.${extensions(platform)}")
      def os = file.newOutputStream()
      os << new URL(androidFileURL).openStream()
      os.close()
      this.logger.println "Saved at ${file.absolutePath}"
  }

  void updateConfigXML(path) {
    def config_xml = new XmlParser().parse(path)
    if (this.version) {
      this.logger.println "Changing 'version' in config.xml to '${this.version}'"
      config_xml.attributes().put('version', this.version)
    }
    if (this.appName) {
      this.logger.println "Changing 'title' in config.xml to '${this.appName}'"
      config_xml.name.replaceNode{name(this.appName)}
    }
    if (this.appName && this.version) {
      new XmlNodePrinter(new PrintWriter(new FileWriter(path))).print(config_xml)
      this.logger.println("Configuration file updated at '${path}'!")
    }
  }

  void buildApp(FilePath workingDir) {
    if (!this.overridedKeys) {
      this.androidKeyId = this.appInfo.keys?.android?.id
      this.iosKeyId = this.appInfo.keys?.ios?.id
    }

    updateConfigXML("${workingDir}/www/config.xml")

    String zippath = prepareZipFilePath(workingDir)
    uploadZipFile(zippath)
    waitForBinaries(workingDir)
  }
}

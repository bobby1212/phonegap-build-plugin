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
  def versionCode = null
  def appBundle = null
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
  private long pollingSleepTime = 10000

  private Map status = [android:'pending', ios:'pending']
  private List downloads = []
  private final static String baseURL = 'https://build.phonegap.com'

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

  public static String createNewApp(token, PrintStream logger) {
    File tmpIndex = new File('index.html', File.createTempDir())
    tmpIndex.createNewFile()
    tmpIndex.deleteOnExit()

    String appId = ""
    def http = new HTTPBuilder(baseURL)
    http.request POST, JSON, { req->
        uri.path =  "/api/v1/apps" //?auth_token=${token}"
    
        MultipartEntity multipartRequestEntity = new MultipartEntity()
        multipartRequestEntity.addPart('file', new FileBody(tmpIndex, "text/html"))
        multipartRequestEntity.addPart('auth_token', new StringBody(token))
        req.entity = multipartRequestEntity
        response.success = { resp, data ->
            appId = data.id
            logger.println "New app ID created: ${appId}"
        }
        
        response.failure = { resp, a ->
          logger.println "\n\nError creating new app  - ${resp.status}\n"
        }
    }
    return appId
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
      sleep(pollingSleepTime)
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
      def androidFileURL = slurper.parseText(new URL(androidURL).text).location.replaceAll(' ','+')
      this.logger.println "Downloading ${extensions(platform)} binary from (${androidFileURL})"

      def file = new File("${workingDir}/${fileBaseName}-${this.version}.${extensions(platform)}")
      def os = file.newOutputStream()
      os << new URL(androidFileURL).openStream()
      os.close()
      this.logger.println "Saved at ${file.absolutePath}"
  }

  void updateConfigXML(path) {
    /**
      Código de version: número, específico
        - versionCode (android)
        - CFBundleVersion (ios)
      Version de app: "0.0.xxxx"
        - version: (android e ios)
    **/
    if (this.version || this.versionCode || this.appName || this.appBundle) {
      def config_xml = new XmlParser().parse(path)
      if (this.version) {
        this.logger.println "Changing 'version' in config.xml to '${this.version}'"
        config_xml.attributes().put('version', this.version)
      }
      if (this.versionCode) {
        this.logger.println "Changing 'versionCode' in config.xml to '${this.versionCode}'"
        config_xml.attributes().put('versionCode', this.versionCode)
        config_xml.attributes().put('android-versionCode', this.versionCode)
        config_xml.attributes().put('CFBundleVersion', this.versionCode)
      }
      if (this.appName) {
        this.logger.println "Changing 'title' in config.xml to '${this.appName}'"
        config_xml.name.replaceNode{name(this.appName)}
      }
      if (this.appBundle) {
        this.logger.println "Changing 'widget id' in config.xml to '${this.appName}'"
        config_xml.attributes().put('id', this.appBundle)
      }
      new XmlNodePrinter(new PrintWriter(new FileWriter(path))).print(config_xml)
      this.logger.println("Configuration file updated at '${path}'!")
    }
  }

  private void updateAppKeys() {
    http.request PUT, TEXT, {req->
        uri.path =  "/api/v1/apps/${this.appId}?auth_token=${this.token}"

        def keysData = [keys:[:]]
        if (this.iosKeyId) {
          keysData.keys.ios = [id: this.iosKeyId]
          if (this.iosKeyPassword)
            keysData.keys.ios.password = this.iosKeyPassword
        }
        if (this.androidKeyId) {
          keysData.keys.android = [id: this.androidKeyId]
          if (this.androidKeyPassword)
            keysData.keys.android.key_pw = this.androidKeyPassword
          if (this.androidKeystorePassword)
            keysData.keys.android.keystore_pw = this.androidKeystorePassword
        }

        send JSON, [
            auth_token: this.token,
            data: keysData
        ]

        response.success = { resp, data ->
            def slurper = new groovy.json.JsonSlurper()
            def json = slurper.parseText(data.text)
            this.logger.println """
              Application updated: ${json?.id}
              Android key:\t${json?.keys?.android?.id} - ${json?.keys?.android?.title}
              iOS key:\t${json?.keys?.ios?.id} - ${json?.keys?.ios?.title}
            """.stripIndent()
        }
        
        response.failure = { resp, a ->
          this.logger.println "ERROR - ${resp.status}"
        }
    }
  }

  void buildApp(FilePath workingDir) {
    if (!this.overridedKeys) {
      this.androidKeyId = this.appInfo.keys?.android?.id
      this.iosKeyId = this.appInfo.keys?.ios?.id
    }

    updateConfigXML("${workingDir}/www/config.xml")
    updateAppKeys()
    String zippath = prepareZipFilePath(workingDir)
    uploadZipFile(zippath)
    waitForBinaries(workingDir)
  }
}

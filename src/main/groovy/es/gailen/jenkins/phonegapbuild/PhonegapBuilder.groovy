package es.gailen.jenkins.phonegapbuild

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.PUT
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.URLENC

//KEEP//@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7')

class PhonegapBuilder {
  def token
  def appId
  def androidKeyId
  def androidKeyPassword
  def iosKeyId
  def iosKeyPassword
  
  private Map appInfo
  private boolean overridedKeys = false
  
  def http = new HTTPBuilder('https://build.phonegap.com')
  
  public PhonegapBuilder(token, appId) {
    this.token = token
    this.appId = appId
    this.refreshAppInfo()
  }
  
  public PhonegapBuilder(token, appId, androidKeyId, iosKeyId) {
    this.token = token
    this.appId = appId
    this.androidKeyId = androidKeyId
    this.iosKeyId = iosKeyId
    this.refreshAppInfo()
    this.overridedKeys = true
  }
  
  private void refreshAppInfo() {
    println  "Refreshing... /api/v1/apps/${appId}?auth_token=${token}"
    this.appInfo = http.get(path: "/api/v1/apps/${appId}", query: [auth_token: token])
  }
  
  void unlockKeys(androidKeyPass, androidKeystorePass, iosPass) {
    if (this.iosKeyId)
      http.request(PUT) {
        uri.path = "/api/v1/keys/ios/${this.iosKeyId}?auth_token=${this.token}"
        send JSON, [
          auth_token: this.token,            
          data: [ password: iosPass ]
        ]
        response.success = { resp, data ->
          println data
        }
      }
        
    if (this.androidKeyId)
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
          println data
        }
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
  
  private String prepareZipFile(workingDir) {
    def ant = new AntBuilder()
    File tmp = File.createTempFile("tmp",".zip")
    tmp.delete()
    tmp.deleteOnExit()
    ant.zip(destfile: tmp.absolutePath) {
      fileset(dir: workingDir) {
          include(name: "**/*.*")
          exclude(name: "scss/**")
          exclude(name: ".git/**")
          exclude(name: "node_modules/**")
          exclude(name: "plugins/**")
          exclude(name: "platforms/**")
          exclude(name: "*.ipa")
          exclude(name: "*.apk")
          exclude(name: "*.xap")
          exclude(name: "bin/*")
          exclude(name: "**/.svn**")
          exclude(name: "**/*.sh")
          exclude(name: "*.zip")          
      }
    }
    tmp.absolutePath
  }
  
  void buildApp(workingDir) {
    if (!this.overridedKeys) {
      this.androidKeyId = this.appInfo.keys?.android?.id
      this.iosKeyId = this.appInfo.keys?.ios?.id
    }
    
    String zippath = prepareZipFile(workingDir)
    println "Using ${zippath}..."
    
    unlockKeys(this.androidKeyPassword, this.iosKeyPassword)
  }
}

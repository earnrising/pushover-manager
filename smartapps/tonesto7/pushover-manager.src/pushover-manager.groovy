/**
 *  Pushover-Manager
 *
 *  Copyright 2018 Anthony Santilli
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.*
def appVer() {"v1.0.20180731"}

definition(
    name: "Pushover-Manager",
    namespace: "tonesto7",
    author: "Anthony Santilli",
    description: "Creates and Manages Pushover devices",
    category: "My Apps",
    iconUrl: "https://raw.githubusercontent.com/tonesto7/pushover-manager/master/images/icon-72.png",
    iconX2Url: "https://raw.githubusercontent.com/tonesto7/pushover-manager/master/images/icon-256.png",
    iconX3Url: "https://raw.githubusercontent.com/tonesto7/pushover-manager/master/images/icon-512.png")


preferences {
    page(name: "mainPage")
    page(name: "messageTest")
}

def appInfoSect()	{
    section() {
        def str = ""
        str += "${app?.name}"
        str += "\nVersion: ${appVer()}"
        paragraph str, image: "https://raw.githubusercontent.com/tonesto7/pushover-manager/master/images/icon-512.png"
    }
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        appInfoSect()
        def validated = (apiKey && userKey && getValidated())
        def devices = validated ? getValidated(true) : []
        section("API Authentication: (${validated ? "Good" : "Missing"})", hidden: validated, hideable: true) {
            input "apiKey", "text", title: "API Key:", description: "Pushover API Key", required: true, submitOnChange: true
            input "userKey", "text", title: "User Key:", description: "Pushover User Key", required: true, submitOnChange: true
        }
        if(validated) {
            section("Statistics:") {
                def msgData = state?.messageData
                def str = ""
                def limit = msgData?.limit
                def remain = msgData?.remain
                def reset = msgData?.resetDt
                str += remain || limit ? "Message Details (Month):" : ""
                str += limit ? "\n • Limit: (${limit})" : ""
                str += remain ? "\n • Remaining: (${remain})" : ""
                str += (remain?.isNumber() && limit?.isNumber()) ? "\n • Used: (${(limit?.toLong() - remain?.toLong())})" : ""
                paragraph str
            }
            section("Clients:") {
                def str = ""
                devices?.each { cl-> str += "\n • ${cl}" }
                paragraph title: "Pushover Clients:", (str != "" ? str : "No Clients Found..."), state: "complete"
                // paragraph title: "What are these?", "A device will be created for each device selected below..."
                // input "pushDevices", "enum", title: "Select PushOver Clients", description: "", multiple: true, required: false, options: devices, submitOnChange: true
            }
            section("Test Notifications:", hideable: true, hidden: true) {
                input "testDevices", "enum", title: "Select Devices", description: "Select Devices to Send Test Notification Too...", multiple: true, required: false, options: devices, submitOnChange: true
                if(settings?.testDevices) {
                    input "testMessage", "text", title: "Test Message to Send:", description: "Enter message to send...", required: false, submitOnChange: true
                    if(settings?.testMessage && settings?.testMessage?.length() > 0) {
                        href "messageTest", title: "Send Message", description: ""
                    }
                }
            }
        }
        state?.testMessageSent = false
    }
}

def isValidated() { }

def getDeviceList() {
    return (settings?.apiKey && settings?.userKey && getValidated()) ? getValidated(true) : []
}

def messageTest() {
    return dynamicPage(name: "messageTest", title: "Notification Test", install: false, uninstall: false) {
        section() {
            if(state?.testMessageSent == true) {
                paragraph "Message Already Sent... Go Back to send again..."
            } else {
                paragraph "Sending ${settings?.testMessage} to ${settings?.testDevices}" 
                sendTestMessage()
            }
            state?.testMessageSent = true
        }
    }
}

def sendTestMessage() {
    app?.getChildDevices(true)?.each { dev->
        if(dev?.getDeviceName()?.toString() in settings?.testDevices) {
            log.debug "sending test message to ${dev?.displayName}"
            dev?.deviceNotification(settings?.testMessage as String)
        }
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"  
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    initialize()
}

def initialize() {
    unsubscribe()
    subscribe(location, "pushoverManagerMsg", locMessageHandler)
    subscribe(location, "pushoverManagerPoll", locMessageHandler)
    sendDeviceListEvent()
}

def sendDeviceListEvent() {
    log.trace "sendDeviceListEvent..."
    sendLocationEvent(name: "pushoverManager", value: "refresh", data: [devices: getDeviceList(), sounds: getSoundOptions()], isStateChange: true, descriptionText: "Pushover Manager Device List Refresh")
}

def uninstalled() {
    log.warn "Uninstalled called... Removing all Devices..."
    addRemoveDevices(true)
}

def locMessageHandler(evt) {
    log.debug "locMessageHandler: ${evt?.jsonData}"
    if (!evt) return
    if (!(settings?.apiKey =~ /[A-Za-z0-9]{30}/) && (settings?.userKey =~ /[A-Za-z0-9]{30}/)) {
        log.error "API key '${apiKey}' or User key '${userKey}' is not properly formatted!"
        return 
    }
    switch (evt?.value) {
        case "sendMsg":
            List pushDevices = []
            if (evt?.jsonData && evt?.jsonData?.devices && evt?.jsonData?.msgData?.size()) {
                evt?.jsonData?.devices?.each { nd->
                    pushoverNotification(nd as String, evt?.jsonData?.msgData)
                }
            }
            break
        case "poll":
            log.debug "locMessageHandler: poll()"
            sendDeviceListEvent()
            break
    }
}

def getValidated(devList=false){
    def validated = false
    def params = [
        uri: "https://api.pushover.net/1/users/validate.json",
        contentType: "application/json",
        body: [
            token: "$apiKey",
            user: "$userKey",
            device: ""
        ]
    ]
    def deviceOptions
    if ((apiKey =~ /[A-Za-z0-9]{30}/) && (userKey =~ /[A-Za-z0-9]{30}/)) {
        try {
            httpPost(params) { resp ->
                // log.debug "response: ${resp.status}"
                if(resp?.status != 200) {
                    // sendPush("ERROR: 'Pushover Me When' received HTTP error ${resp?.status}. Check your keys!")
                    log.error "Received HTTP error ${resp.status}. Check your keys!"
                } else {
                    if(devList) {
                        if(resp?.data && resp?.data?.devices) {
                            // log.debug "Found (${resp?.data?.devices?.size()}) Pushover Devices..."
                            deviceOptions = resp?.data?.devices
                            state?.pushoverDevices = resp?.data?.devices
                        } else { 
                            log.error "Device List is empty"
                            state?.pushoverDevices = []
                        }
                    } else {
                        // log.debug "Keys Validated..."
                        validated = true
                    }
                }
            }
        } catch (Exception ex) {
            if(ex instanceof groovyx.net.http.HttpResponseException && ex?.response) {
                log.error "getValidated() HttpResponseException | Status: (${ex?.response?.status}) | Data: ${ex?.response?.data}"
            } else {
                log.error "An invalid key was probably entered. PushOver Server Returned: ${ex}"
            }
        } 
    } else {
        log.error "API key '${apiKey}' or User key '${userKey}' is not properly formatted!"
    }
    return devList ? deviceOptions : validated
}

def getSoundOptions() {
    // log.debug "Generating Sound Notification List..."
    def myOptions = [:]
    try {
        httpGet(uri: "https://api.pushover.net/1/sounds.json?token=${settings?.apiKey}") { resp ->
            if(resp?.status == 200) {
                // log.debug "Found (${resp?.data?.sounds?.size()}) Sounds..."
                def mySounds = resp?.data?.sounds
                // log.debug "mySounds: $mySounds"
                mySounds?.each { snd->
                    myOptions["${snd?.key}"] = snd?.value
                }
            } else {
                sendPush("ERROR: 'Pushover Me When' received HTTP error ${resp?.status}. Check your keys!")
                log.error "Received HTTP error ${resp?.status}. Check your keys!"
            }
        }
    } catch (Exception ex) {
        if(ex instanceof groovyx.net.http.HttpResponseException && ex?.response) {
            log.error "getSoundOptions() HttpResponseException | Status: (${ex?.response?.status}) | Data: ${ex?.response?.data}"
        }
    }
    state?.soundOptions = myOptions
    return myOptions
}

def filterPriorityMsg(msg, msgPr) {
    if(msg?.startsWith("[L]")) { 
        msgPr = "-1"
        msg = msg?.minus("[L]")
    } else if(msg?.startsWith("[N]")) {
        msgPr = "0"
        msg = msg?.minus("[N]")
    } else if(msg?.startsWith("[H]")) {
        msgPr = "1"
        msg = msg?.minus("[H]")
    } else if(msg?.startsWith("[E]")) {
        msgPr = "2"
        msg = msg?.minus("[E]")
    }
    return [msg: msg, msgPr: msgPr]
}

void pushoverNotification(deviceName, msgData) {
    log.debug "pushoverNotification($deviceName, $msgData)"
    if(deviceName && msgData) {
        if(msgData?.message != null && msgData?.message?.length() > 0 && deviceName && settings?.apiKey && settings?.userKey) {
            def hasImage = (msgData?.image && msgData?.image?.url && msgData?.image?.type)
            def filtr = filterPriorityMsg(msgData?.message, msgData?.priority)
            String message = filtr?.msg
            String priority = filtr?.msgPr ?: "0"
            def body
            Map bodyItems = [
                token: settings?.apiKey?.trim(),
                user: settings?.userKey?.trim(),
                title: msgData?.title,
                message: message,
                priority: priority,
                device: deviceName,
                retry: msgData?.retry ?: 30,
                expire: msgData?.expire ?: 10800
            ]
            if(msgData?.sound) { bodyItems?.sound = msgData?.sound }
            if(msgData?.url) { bodyItems?.url = msgData?.url }
            if(msgData?.url_title) { bodyItems?.url_title = msgData?.urlTitle }
            if(msgData?.timestamp) { bodyItems?.timestamp = msgData?.timestamp }
            if(msgData?.html == true) { bodyItems?.html = 1 }
            
            
            if(hasImage) {
                String bodyStr = ""
                bodyItems?.each { k, v ->
                    bodyStr += "------pushoverManagerApp\r\n"
                    bodyStr += "Content-Disposition: form-data; name=\"${k}\"\r\n\r\n${v}\r\n"
                }
                if(msgData?.image && msgData?.image?.url && msgData?.image?.type) {
                    bodyStr += "------pushoverManagerApp\r\n"
                    bodyStr += "Content-Disposition: form-data; name=\"attachment\"; filename=\"${msgData?.image?.name}\"\r\nContent-Type: ${msgData?.image?.type}\r\n\r\n\r\n${getFile(msgData?.image?.url, msgData?.image?.type)}"
                }
                bodyStr += "------pushoverManagerApp--"
                body = bodyStr as String
            } else {
                body = new JsonOutput().toJson(bodyItems)
            }
            Map params = [
                uri: "https://api.pushover.net/1/messages.json",
                contentType: "multipart/form-data; boundary=----pushoverManagerApp",
                body: body
            ]
            
            // log.debug "params: $params"
            try {
                httpPost(params) { resp ->
                    def limit = resp?.getHeaders("X-Limit-App-Limit")
                    def remain = resp?.getHeaders("X-Limit-App-Remaining")
                    def resetDt = resp?.getHeaders("X-Limit-App-Reset")
                    if(resp?.status == 200) {
                        log.debug "Message Received by Pushover Server | Monthly Messages Remaining (${remain?.value[0]} of ${limit?.value[0]})"
                        state?.messageData = [lastMessage: msgData?.message, lastMessageDt: formatDt(new Date()), remain: remain?.value[0], limit: limit?.value[0], resetDt: resetDt?.value[0]]
                    } else if (resp?.status == 429) { 
                        log.warn "Can't Send Notification... You have reached your (${limit?.value[0]}) notification limit for the month"
                    } else {
                        sendPush("pushoverNotification() ERROR: 'Pushover' received HTTP error ${resp?.status}. Check your keys!")
                        log.error "Received HTTP error ${resp?.status}. Check your keys!"
                    }
                }
            } catch (ex) {
                // if(ex instanceof groovyx.net.http.HttpResponseException && ex?.response) {
                    log.error "pushoverNotification() HttpResponseException | Status: (${ex?.response?.status}) | Data: ${ex?.response?.getData()}"
                // } else {
                    log.error "pushoverNotification Exception: ${ex?.message}" 
                // }
            }
        }
    }
}

def getFile(url, fileType, base64=false) {
    try {
        def params = [uri: url, contentType: "$fileType"]
        httpGet(params) { resp ->
            if(resp?.status == 200) {
                if(resp?.data) {
                    Byte[] bytes = resp?.data?.getBytes()
                    def size = resp?.getHeaders("Content-Length")
                    if(size?.value && size?.value[0] && size?.value[0]?.isNumber()) {
                        if(size?.value[0]?.toLong() > 2621440) {
                            log.debug("FileSize: (${getFileSize(size?.value[0])})")
                            log.warn "unable to encode file because it is larger than the 2.5MB size limit"
                            return null
                        }
                    }
                    if(!base64) { return bytes }
                    String enc = bytes?.encodeBase64() as String
                    return enc ? "data:${fileType};base64,${enc?.toString()}" : null
                }
            } else {
                log.error("getFile Resp: ${resp?.status} ${url}")
                return null
            }
        }
    } catch (ex) {
        if(ex instanceof groovyx.net.http.ResponseParseException) {
            if(ex?.statusCode != 200) {
                log.error("getFile Resp: ${ex?.statusCode} ${url}")
                log.error "getFile Exception:", ex
            }
        } else if(ex instanceof groovyx.net.http.HttpResponseException && ex?.response) {
            log.error("getFile Resp: ${ex?.response?.status} ${url}")
        } else {
            log.error "getFile Exception:", ex
        }
        return null
    }
}

def getFileSize(size) {
    String modifiedFileSize = null;
    double fileSize = 0.0;
    fileSize = size?.toDouble()
    if (fileSize < 1024) {
        modifiedFileSize = String.valueOf(fileSize).concat("B")
    } else if (fileSize > 1024 && fileSize < (1024 * 1024)) {
        modifiedFileSize = String.valueOf(Math.round((fileSize / 1024 * 100.0)) / 100.0).concat("KB")
    } else {
        modifiedFileSize = String.valueOf(Math.round((fileSize / (1024 * 1204) * 100.0)) / 100.0).concat("MB")
    }
    return modifiedFileSize
}

def getDtNow() {
    def now = new Date()
    return formatDt(now, false)
}

def formatDt(dt, mdy = true) {
    def formatVal = mdy ? "MMM d, yyyy - h:mm:ss a" : "E MMM dd HH:mm:ss z yyyy"
    def tf = new java.text.SimpleDateFormat(formatVal)
    if(location?.timeZone) { tf.setTimeZone(location?.timeZone) }
    return tf.format(dt)
}
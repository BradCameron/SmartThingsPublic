def appVersion() {"6.7+001-%REL_ENV%"}	// Major.Minor+buildNo-Env
def helpURL() {"https://www.ActionTiles.com/at6g/help"}
def getSensorCapabilities() {"https://%REL_ENV%.ActionTiles.com/sensors.json"}
def getPowerThresholds() {"https://%REL_ENV%.ActionTiles.com/powerthresholds.json"}
//def getSensorCapabilities() {"https://www.ActionTiles.com/sensors.json"}
//def getPowerThresholds() {"https://www.ActionTiles.com/powerthresholds.json"}

include 'asynchttp_v1'
/**
 * ActionTiles (Connect) %REL_ENV%
 *
 * Copyright 2017 Thingterfaces LP
 *
 * Program, code and related communication is strictly confidential.
 *	 Permission granted to shared ONLY between the authors (Alex & Terry of Thingterfaces LP)
 *	 and authorized representatives / employees of SmartThings.
 *	 Please take care and minimize distribution. Destroy copies when no longer required.
 *
 */

definition(
	name: "ActionTiles (Connect) %REL_ENV%",
	namespace: "ActionTiles",
	author: "Thingterfaces LP",
	description: "Web service to communicate with ActionTiles Dashboard (https://ActionTiles.com).",
	category: "Convenience",
	iconUrl: "https://%REL_ENV%.ActionTiles.com/icons/saX1.png",
	iconX2Url: "https://%REL_ENV%.ActionTiles.com/icons/saX2.png",
	iconX3Url: "https://%REL_ENV%.ActionTiles.com/icons/saX3.png",
	oauth: [displayName: "ActionTiles (Connect) %REL_ENV%", displayLink: "https://ActionTiles.com/help/authorize"]
)
{
	appSetting "apiPath"
	appSetting "apiKey"
}
	
preferences(oauthPage: "deviceAuthorization") {
	page(name: "deviceAuthorization", install: true, uninstall: true) {
		section( "ActionTiles (Connect) %REL_ENV%" ) {
			paragraph "ActionTiles™ - A SmartThings web client.\nThe homepage for your home!™" +
					"\nCopyright © 2017 Thingterfaces LP"
			href url:"${helpURL()}/saconfig", style:"embedded", required:false, title:"Help & more information...", description:"www.ActionTiles.com/help", image:"https://%REL_ENV%.ActionTiles.com/icons/saX1.png"
		}
		
		section( "SmartApp can only access the specific Things that you authorize here (plus arm your Smart Home Monitor, run Routines, and change Modes). \n" +
				"\nActionTiles suggests that you authorize ALL or MOST of your Things, so that they will available in the ActionTiles Web App's Inventory. \n" +
				"\n[Version ${appVersion()}] \n"
			 ) {
			input "sensors", "capability.sensor", title:"1) Select any or all of your Things:", multiple: true, required: false
			input "actuators", "capability.actuator", title:"2) Add any or all Things missing from the previous list (duplicates OK):", multiple: true, required: false
			input "switches", "capability.switch", title:"3) Add any or all Things missing from the previous lists (duplicates OK):", multiple: true, required: false
			input "batteries", "capability.battery", title:"4) If desired Things are not available in any above lists, try this list:", multiple: true, required: false
			input "temperatures", "capability.temperatureMeasurement", title:"5) Or this final list (Contact Support@ActionTiles.com if any Thing is still not shown):", multiple: true, required: false
		}
		
		if (state) {
			section( "ActionTiles Stream Status" ) {
				if (isLocationStreaming()) {
					paragraph "$location.name is currently streaming event data to ActionTiles."
				} else {
					paragraph "$location.name is currently not streaming any event data to ActionTiles."
				}
			}
		}
		remove("Uninstall", "Are you sure you want to uninstall ActionTiles?")
	}
}

def installed() {
	log.info "Installing the SmartApp"
	initialize()
}

def updated() {
	log.info "Updating the SmartApp"
	initialize()
}

def initialize() {
	log.info "Initializing the SmartApp"
	state.SmartAppVersion = appVersion()
	log.info "ActionTiles (Connect) SmartApp Version: $state.SmartAppVersion"
	checkEventStreamStatus([initialize: true])
	[status: "ok"]
}

def reset() {
	log.info "Resetting the SmartApp"
	state.remove("throttle")
	unsubscribe()
	unschedule()
}

def uninstalled() {
	log.info "Uninstalling ActionTiles"
	revokeAccessToken()
	
	sendToFB([
		"locations/$location.id": [name: escapeUTF8(location.name), uninstalled: true, muted: true, timestamp: [".sv" : "timestamp"]],
		"properties/$location.id" : [uninstalled: true, muted: true, timestamp: [".sv" : "timestamp"]]
	])
}

def getWeatherProperties() {["city", "weather", "wind", "weatherIcon", "forecastIcon", "feelsLike", "percentPrecip", "localSunrise", "localSunset"]}

def subscribe() {
	log.info "Subscribing to events"
	
	subscribe(location, "mode", locationEventHandler)
	subscribe(location, "alarmSystemStatus", locationEventHandler)
	
	asynchttp_v1.get(subscribeCallback, [uri: getSensorCapabilities()])
	
	return [status : "ok"]
}

def powerThresholdsCallback(response, data) {
	log.info "Fetched PowerThresholds: $response.json"
	state.powerThreshold0100 = response.json.powerThreshold0100
	state.powerThreshold0500 = response.json.powerThreshold0500
	state.powerThreshold1000 = response.json.powerThreshold1000
	state.powerThreshold1000 = response.json.powerThreshold1000
	state.powerThreshold5000 = response.json.powerThreshold5000
	state.powerThresholdOver = response.json.powerThresholdOver
}

def subscribeCallback(response, data) {
	log.info "subscribe to $response.json"
	
	def devices = getUniqueDeviceSet()
	log.debug "subscribeCallback $devices" 
	
	devices?.each {device ->
		device.capabilities.each { capability ->
			if (response.json[capability.name]) {
				capability.attributes?.each {attribute ->
					log.debug("subscribing $device $attribute.name")
					subscribe(device, "$attribute.name", sensorEventHandler)
				}
			}
		}
		
		if (isWeather(device)) {
			getWeatherProperties().each {
				subscribe(device, it, sensorEventHandler)
			}
		}
	}
}

def refreshWeather() {
	try {
		def devices = [sensors ?: []].flatten()
		devices.each {
			if (isWeather(it) && it.hasCommand("refresh")) {
				it.refresh()
			}
		}
	} catch (e) {
		log.error("failed to refresh weather")
		log.error(e)
	}
}

def checkEventStreamStatus(data) {
	log.info "Checking Event Stream Status"
	
	def uri = "${getSanitizedFBUri()}/eventStreamStatus/$location.id/.json?auth=$appSettings.apiKey"
		
	asynchttp_v1.get(checkEventStreamStatusCallback, [uri: uri], data)
}

def checkEventStreamStatusCallback(response, data) {
	if (response.json?.streaming in [null, true]) {
		state.eventStreamStatus = "STREAMING"
	} else {
		state.eventStreamStatus = response.json?.streaming
	}
	
	log.info "checkEventStreamStatusCallback: Location Event Stream Status is $state.eventStreamStatus"
	if (isLocationStreaming()) {
		if (data.initialize) {
			reset()
			initializeLocation()
		}
	} else {
		mute()
	}
}

def initializeLocation() {
	log.debug "initializing with settings: $settings"
	synchronize()
	subscribe()
	runEvery3Hours(synchronize)
	runEvery30Minutes(refreshWeather)
	runEvery30Minutes(checkEventStreamStatus, ["data": ["initialize": false]])
	runEvery15Minutes(pollEnergyValues)
}

def isLocationStreaming() {
	return state.eventStreamStatus == "STREAMING"
}

def synchronize() {
	log.info "synchronize: Location Event Stream Status is $state.eventStreamStatus"
	
	if (isLocationStreaming()) {
		log.info "Synchronizing location data"
		
		asynchttp_v1.get(synchronizeCallback, [uri: getSensorCapabilities()])
		asynchttp_v1.get(powerThresholdsCallback, [uri: getPowerThresholds()])
	}
	
	[status: "ok", eventStreamStatus: state.eventStreamStatus]
}

def getUniqueDeviceSet() {
	Set deviceSet = []
	
	[sensors, actuators, switches, temperatures, batteries].each {
		it?.each {
			deviceSet.add(it)
		}
	}
	
	deviceSet
}

def isWeather(device) {
	device.hasAttribute("weather") && device.hasAttribute("weatherIcon")
}

def pollEnergyValues() {
	def fanout = [:]
	
	getUniqueDeviceSet()?.each {
		if (it.hasCapability("Energy")) {
			state.energyCache = state.energyCache ?: [:]
			def value = device.currentEnergy
			if (state.energyCache["$it.id"] != value) {
				state.energyCache["$it.id"] = value
				
				fanout["properties/$location.id/devices/$it.id/energy"] = value
			}
		}
	}
	
	if (fanout) {
		sendToFB(fanout)
	}
	
	fanout
}

def synchronizeCallback(response, data) {
	def deviceDetails = [:]
	def deviceProperties = [:]
	
	def devices = getUniqueDeviceSet()
	log.info "synchronizeCallback devices $devices" 
	
	devices?.each {device ->
		deviceProperties["$device.id"] = ["name": escapeUTF8(device.displayName)]
		
		deviceDetails[device.id] = [name: escapeUTF8(device.displayName), typeName: device.typeName, capabilities: [:]]
		
		device.capabilities.each { capability ->
			deviceDetails[device.id]["capabilities"][capability.name] = true
			
			if (response.json[capability.name]) {
				capability.attributes?.each {attribute ->
					try {
						deviceProperties["$device.id"]["$attribute.name"] = device.currentValue(attribute.name)
					} catch (e) {
						log.error("failed to read $attribute attribute from $device.id $device")
						log.error(e)
						
						deviceProperties["$device.id"]["$attribute.name"] = "ERR"
					}
				}
			}
		}
		
		if (isWeather(device)) {
			deviceDetails[device.id]["capabilities"]["Weather"] = true
			getWeatherProperties().each {
				deviceProperties["$device.id"]["$it"] = device.currentValue(it)
			}
		}
		
		if (device.hasCapability("Energy")) {
			deviceProperties["$device.id"].energy = device.currentEnergy
		}
	}
	
	def fanout = [
		"locations/$location.id/devices" : deviceDetails, 
		"locations/$location.id/name" : escapeUTF8(location.name), 
		"locations/$location.id/temperatureScale" : getTemperatureScale(), 
		"locations/$location.id/timeZone" : location?.timeZone, 
		"locations/$location.id/latitude" : location?.latitude, 
		"locations/$location.id/longitude" : location?.longitude, 
		"locations/$location.id/muted" : null,
        "locations/$location.id/uninstalled" : null,
		"properties/$location.id/devices/" : deviceProperties, 
		"properties/$location.id/location" : getLocationProperties(),
		"properties/$location.id/muted" : null,
        "properties/$location.id/uninstalled" : null,
		"eventStreamStatus/$location.id/streaming" : null
	]
	
	try {
		sendToFB(fanout)
	} catch (e) {
		def message = "method 'synchronizeCallback' failed to write to FB. $e"
		log.error message
		
		if (params.full) {
			return [error: e, data: fanout]
		} else {
			return httpError(400, message)
		}
	}
}

def getLocationProperties() {
	[mode: location.currentMode?.id, shm: location.currentState("alarmSystemStatus")?.value, modes : getModes(), routines: getRoutines(), name: escapeUTF8(location.name), temperatureScale: getTemperatureScale()]
}

def sensorEventHandler(event) {
	if (event.isStateChange()) {
		def eventTO = getEventTO(event)
		log.debug "A sensor event occurred: $eventTO"
		
		def isThrottleEvent = event.name in ["power"]
		
		if (!isThrottleEvent || isThrottleEvent && isSignificantStateChange(event.device.id, event.name, event.value)) {
			addEventStats(event.device.id, event.name)
			sendToFB(["properties/$location.id/devices/$event.device.id/$event.name" : event.value])
		}
	}
}

def addEventStats(id, event) {
	state.stats = state.stats ?: [initiated: now(), date: new Date(), events: [:]]
	state.stats.events[id] = state.stats.events[id] ?: [:]
	state.stats.events[id][event] = (state.stats.events[id][event] ?: 0) + 1
	
	state.stats.total = (state.stats.total ?: 0) + 1
}

def isSignificantStateChange(deviceId, eventName, newValue) {
	try {
		def oldValue = (state?.throttle?."$deviceId-$eventName" ?: 0) as double
		newValue = newValue as double
		
		/* Power change reporting threshold in percent: Set based on size of Value. */
		def threshold = 10 as double
		switch (Math.max(oldValue, newValue)) {
			case { it <= 100 }:
				threshold = state.powerThreshold0100 ?: 20
				break
			case { it <= 500 }:
				threshold = state.powerThreshold0500 ?: 15
				break
			case { it <= 1000 }:
				threshold = state.powerThreshold1000 ?: 5
				break
			case { it <= 5000 }:
				threshold = state.powerThreshold5000 ?: 5
				break
			case { it > 5000 }:
				threshold = state.powerThresholdOver ?: 3
				break
		}

		def deltaPercent = (Math.min(oldValue,newValue) > 0) ? (((oldValue - newValue) / Math.min(oldValue,newValue)).abs() * 100).round(1) : 100
		log.debug "Power: oldValue: $oldValue, newValue: $newValue, delta: ${deltaPercent}%, threshold: ${threshold}%"
		if (!oldValue || !newValue || ((newValue - oldValue) / Math.min(oldValue,newValue)).abs() >= (threshold/100)) {
			state.throttle = state.throttle ?: [:]
			state.throttle."$deviceId-$eventName" = newValue
			return true
		} else {
			return false
		}
	} catch (e) {
		log.error e
		return true
	}
}

def locationEventHandler(event){
	if (event.isStateChange()) {
		
		def eventTO = getEventTO(event)
		log.debug "locationEventHandler $eventTO"
		
		addEventStats("location", event.name)
		sendToFB(["properties/$location.id/location" : getLocationProperties()])
	}
}

def getEventTO(event) {
	[
		name: event.name,
		deviceId: event.deviceId,
		isoDate: event.isoDate,
		unit: event.unit,
		value: event.value
	]
}

def getSanitizedFBUri() {
	def uri = appSettings.apiPath.trim()
	
	while (uri[-1] == '/') {
		uri = uri[0..-2]
	}
	
	uri
}

def sendToFB(data) {
	try {
		def params = [
			uri: "${getSanitizedFBUri()}/.json", 
			query: [
				print:	"silent",
				auth:	appSettings.apiKey
			],
			body: data
		]
		log.debug "sending data to AT"
		asynchttp_v1.patch(null, params)
	} catch (e) {
		log.error ("error writing to database. $e")
	}
}

def escapeUTF8(string) {
    try {
        return string.collectReplacements{it >= 128 ? "\\u" + String.format("%04X", (int) it) : null}
    } catch (e) {
        log.error "error escapeUTF8 $e"
    }
   
    return string
}

def getModes() {
	def modes = [:]
	location.modes?.each { modes[it.id] = escapeUTF8(it.name) }
	modes
}

def getRoutines() {
	def routines = [:]
	location?.helloHome?.getPhrases()?.each {routines[it.id] = escapeUTF8(it.label)}
	routines
}

mappings {
	path("/device/:id/:command/") {action: [GET: "doCommand0"]}
	path("/device/:id/:command/:param") {action: [GET: "doCommand1"]}
	path("/location/routine/:id") {action: [GET: "doRoutine"]}
	path("/location/alarm/:state") {action: [GET: "doAlarm"]}
	path("/location/mode/:id") {action: [GET: "doMode"]}
	
	path("/location/synchronize") {action: [GET: "synchronize"]}
	path("/location/initialize") {action: [GET: "initialize"]}
	path("/location/mute") {action: [GET: "mute"]}
	path("/location/unmute") {action: [GET: "unmute"]}
	path("/location/ping") {action: [GET: "ping"]}
	path("/location/reset-stats") {action: [GET: "resetStats"]}
	
	path("/weather") {action: [GET: "getWeather"]}
}

def doCommand0() {
	log.info "executing '/device/$params.id/$params.command' endpoint"
	def devices = [sensors ?: [], actuators ?: [], switches ?: [], temperatures ?: [], batteries ?: []].flatten()
	def device = devices?.find{it.id == params.id}
	if (device) {
		if (device.hasCommand("$params.command")) {
			try {
				if (params.command == "setColor") {
					def color = [:]
					if (params.hue != null) {
						color.hue = params.hue as Integer
						device.setHue(color.hue)
					}
					
					if (params.saturation != null) {
						color.saturation = params.saturation as Integer
						device.setSaturation(color.saturation)
					}
					
					if (params.hex != null) {
						color.hex = "#" + params.hex
					}
					device.setColor(color)
					log.debug "setting device color $color"
				} else {
					device."$params.command"()
				}
				log.debug "command $params.command executed successfully"
				return [status : "ok"]
			} catch (e) {
				def message = "device failed to execute command $params.command, $e"
				log.error message
				return httpError(400, message)
			}
		} else {
			def message = "device does not support command $params.command"
			log.error message
			return httpError(405, message)
		}
	} else {
		def message = "device not found"
		log.error message
		return httpError(404, message)
	}
}

def doCommand1() {
	if( params.argType ) {
		log.info "executing '/device/$params.id/$params.command/$params.param?argType=$params.argType' endpoint"
	} else {
		log.info "executing '/device/$params.id/$params.command/$params.param' endpoint"
	}

	def devices = [sensors ?: [], actuators ?: [], switches ?: [], temperatures ?: [], batteries ?: []].flatten()
	def device = devices?.find{it.id == params.id}
	if (device) {
		if (device.hasCommand("$params.command")) {
			def arg = params.param
			try {
				
				try {
					if (params.argType == "int") {
						arg = arg as Integer
					} else if (params.argType == "float") {
						arg = arg as Float
					}
				} catch (e) {
					def message = "failed to cast $arg to $params.argType but will attempt to call the command with the original value"
					log.error(message)
					log.error(e)
				}
				
				device."$params.command"(arg)
				log.debug "command $params.command($arg) executed successfully"
				
				return [status : "ok"]
			} catch (e) {
				def message = "failed to execute command $params.command($arg) for $device.id $device, $e"
				log.error message
				
				return httpError(400, message)
			}
		} else {
			def message = "device $params.id does not have command $params.command"
			log.error message
			
			return httpError(405, message)
		}
	} else {
		def message = "device $params.id not found"
		log.error(message)
		
		return httpError(404, message)
	}
}

def doRoutine() {
	log.info "executing 'routine/$params.id' endpoint"
	def routine = location?.helloHome?.getPhrases()?.find {it.id == params.id}
	if (routine) {
		log.info "executing routine $routine"
		location.helloHome?.execute(routine.label)
		return [status : "ok"]
	} else {
		def message = "routine not found"
		log.error message
		
		return httpError(404, message)
	}
}

def doAlarm() {
	log.info "executing 'alarm/$params.state' endpoint"
	sendLocationEvent(name: "alarmSystemStatus" , value : params.state)
	[status : "ok"]
}

def doMode() {
	log.info "executing 'mode/$params.id' endpoint"
	def mode = location.modes?.find {it.id == params.id}
	if (mode) {
		log.info "executing mode $mode"
		setLocationMode(mode)
		return [status: "ok"]
	} else {
		def message = "mode not found"
		log.error message
		
		return httpError(404, message)
	}
}

def mute() {
	log.info "mute location"
	
	reset()
	
	sendToFB([
		"locations/$location.id": [name: escapeUTF8(location.name), muted: true, uninstalled: null, timestamp: [".sv" : "timestamp"]],
		"properties/$location.id" : [muted: true, uninstalled: null, timestamp: [".sv" : "timestamp"]],
		"eventStreamStatus/$location.id/streaming" : false
	])
	
	state.eventStreamStatus = "MUTED"
	
	[status: "ok"]
}

def unmute() {
	log.info "unmute location"
	state.eventStreamStatus = "STREAMING"
	initializeLocation()
	
	[status: "ok"]
}

def ping() {
	log.info "pinging location"
	[status: "ok", state: state, appVersion: appVersion()]
}

def resetStats() {
	log.info "reset stats"
	
	state.stats = null
	[status: "ok"]
}

def getWeather() {
	def response = [:]
	if (params.features) {
		params.features.split("+")?.each {
			if (params.location) {
				response[it] = getWeatherFeature(it, params.location)
			} else {
				response[it] = getWeatherFeature(it)
			}
		}
	}
	return response
}

/* =========== */
/* End of File */
/* =========== */

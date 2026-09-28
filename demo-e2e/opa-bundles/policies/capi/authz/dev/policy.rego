package capi.authz.dev

default allow := false

# METADATA
# entrypoint: true
allow if service_is_subscribed

service_is_subscribed if {
	input.azp in data.subscriptions[input.service]
}
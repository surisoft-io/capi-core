package capi.owner_only

default allow = false

allow if {
    input.realm_access.roles[_] == "admin"
}

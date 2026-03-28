package capi.admin_only

default allow = false

allow if {
    input.realm_access.roles[_] == "admin"
}

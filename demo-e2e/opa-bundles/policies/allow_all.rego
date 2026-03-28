package capi.allow_all

default allow = false

allow if {
    input.realm_access.roles[_] == "admin"
}

allow if {
    input.realm_access.roles[_] == "manager"
}

allow if {
    input.realm_access.roles[_] == "viewer"
}

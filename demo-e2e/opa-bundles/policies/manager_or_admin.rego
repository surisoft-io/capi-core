package capi.manager_or_admin

default allow = false

allow if {
    input.realm_access.roles[_] == "admin"
}

allow if {
    input.realm_access.roles[_] == "manager"
}

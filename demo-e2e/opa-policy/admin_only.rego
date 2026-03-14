package capi.admin_only

default allow = false

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "admin"
}

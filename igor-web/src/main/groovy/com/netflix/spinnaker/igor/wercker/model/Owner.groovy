package com.netflix.spinnaker.igor.wercker.model

class Owner {
    /*"owner": {
        "type": "wercker",
        "name": "desagar",
        "avatar": {
            "gravatar": "da2aaeec804e118066a4ea8a396f95a8"
        },
        "userId": "5abe5aafa1fe0301005bcdb6",
        "meta": {
            "username": "desagar",
            "type": "user",
            "werckerEmployee": false
        }
    },*/
    String name
    String type
    String userId
    OwnerMetadata meta

    static class OwnerMetadata {
        String username
        String type
        boolean werckerEmployee
    }
}

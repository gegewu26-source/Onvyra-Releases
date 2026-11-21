package com.constx.onvyra.data.ai.tools

/** App-side tickets survive rebuilding Tool declarations while a real user approval is pending. */
internal object AndroidControlApprovals {
    private data class Ticket(val fingerprint:String,val approved:Boolean=false)
    private val tickets=LinkedHashMap<String,Ticket>()
    private fun key(owner:String,name:String,args:String)="$owner|$name|$args"
    @Synchronized fun remember(owner:String,name:String,args:String,fingerprint:String?) {
        val key=key(owner,name,args)
        if(fingerprint==null)tickets.remove(key) else tickets[key]=Ticket(fingerprint)
        while(tickets.size>128)tickets.remove(tickets.keys.first())
    }
    @Synchronized fun approve(owner:String,name:String,args:String) {
        val key=key(owner,name,args)
        tickets[key]?.let { tickets[key]=it.copy(approved=true) }
    }
    @Synchronized fun consume(owner:String,name:String,args:String):String? =
        tickets.remove(key(owner,name,args))?.takeIf {it.approved}?.fingerprint
}

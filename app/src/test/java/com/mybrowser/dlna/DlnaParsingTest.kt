package com.mybrowser.dlna

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parser-only tests; no multicast socket or physical renderer is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DlnaParsingTest {

    @Test
    fun `ssdp parser accepts only http urls with a host`() {
        val reply = SsdpDiscovery.parse(
            "HTTP/1.1 200 OK\r\n" +
                "LOCATION: http://192.168.1.10:1400/device.xml\r\n" +
                "USN: uuid:renderer-1\r\n" +
                "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n",
        )
        assertEquals("uuid:renderer-1", reply?.usn)
        assertEquals("http://192.168.1.10:1400/device.xml", reply?.location)

        assertNull(
            SsdpDiscovery.parse(
                "HTTP/1.1 200 OK\r\nLOCATION: javascript:alert(1)\r\n" +
                    "ST: ssdp:all\r\n\r\n",
            ),
        )
    }

    @Test
    fun `device description resolves safe control urls`() {
        val reply = SsdpDiscovery.Reply(
            location = "http://192.168.1.10:1400/device.xml",
            usn = "uuid:renderer-1",
            server = null,
            searchTarget = "ssdp:all",
        )
        val xml = """
            <root><device>
              <friendlyName>Living Room</friendlyName>
              <manufacturer>Example</manufacturer>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/av</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/rc</controlURL></service>
              </serviceList>
            </device></root>
        """.trimIndent()

        val device = DeviceDescription.parse(xml, reply)
        assertEquals("Living Room", device?.friendlyName)
        assertEquals("http://192.168.1.10:1400/upnp/control/av", device?.controlUrl)
        assertEquals("http://192.168.1.10:1400/upnp/control/rc", device?.renderingControlUrl)
        assertTrue(DeviceDescription.absolute(reply.location, "/upnp/control/av")!!.startsWith("http://"))
        assertNull(DeviceDescription.absolute(reply.location, "ftp://example.com/control"))
    }
}

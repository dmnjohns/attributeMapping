/***
 *   Copyright 2016 Rackspace US, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.rackspace.identity.components.cli

import java.io.File
import java.io.PrintStream
import java.io.InputStream

import java.net.URI

import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

import org.clapper.argot.ArgotConverters._
import org.clapper.argot.{ArgotParser, ArgotUsageException}

import com.martiansoftware.nailgun.NGContext

import net.sf.saxon.s9api.Serializer
import net.sf.saxon.s9api.Destination


import com.rackspace.identity.components.AttributeMapper

object URLResolver {
  def toAbsoluteSystemId(systemId : String) : URI = {
    toAbsoluteSystemId(systemId, (new File(System.getProperty("user.dir")).toURI().toString))
  }

  def toAbsoluteSystemId(systemId : String, base : String) : URI = {
    val inURI = new URI(systemId)
    if (!inURI.isAbsolute()) {
      (new URI(base)).resolve(systemId)
    } else {
      inURI
    }
  }
}

object AttribMap {
  val title = getClass.getPackage.getImplementationTitle
  val version = getClass.getPackage.getImplementationVersion

  def parseArgs(args: Array[String], base : String,
                in : InputStream, out : PrintStream, err : PrintStream) : Option[(Source, Source, Destination, Boolean)] = {

    val parser = new ArgotParser("attribmap", preUsage=Some(s"$title v$version"))

    val policy = parser.parameter[String]("policy",
                                          "Attribute mapping policy",
                                          false)

    val assertion = parser.parameter[String]("assertion",
                                             "The assertion to translate based on policy",
                                             false)

    val output = parser.parameter[String]("output",
                                          "Output file. If not specified, stdout will be used.",
                                          true)

    val help = parser.flag[Boolean] (List("h", "help"),
                                     "Display usage.")

    val useSAML = parser.flag[Boolean] (List("s", "saml"),
                                        "Output in SAML format")

    val printVersion = parser.flag[Boolean] (List("version"),
                                             "Display version.")


    def policySource : Source = new StreamSource(URLResolver.toAbsoluteSystemId(policy.value.get, base).toString)
    def assertionSource : Source = new StreamSource(URLResolver.toAbsoluteSystemId(assertion.value.get, base).toString)
    def destination : Destination = {
      if (output.value.isEmpty) {
        AttributeMapper.processor.newSerializer(out)
      } else {
        AttributeMapper.processor.newSerializer(new File(URLResolver.toAbsoluteSystemId(output.value.get, base)))
      }
    }
    try {
      parser.parse(args)

      if (help.value.getOrElse(false)) {
        parser.usage() // throws ArgotUsageException
      }

      if (printVersion.value.getOrElse(false)) {
        err.println(s"$title v$version")
        None
      } else {
        Some((policySource, assertionSource, destination, useSAML.value.getOrElse(false)))
      }
    } catch {
      case e: ArgotUsageException => err.println(e.message)
                                     None
      case iae : IllegalArgumentException => err.println(iae.getMessage)
                                             None
    }
  }
}
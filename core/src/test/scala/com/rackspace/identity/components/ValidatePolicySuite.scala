/**
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
package com.rackspace.identity.components

import java.io.File
import javax.xml.transform.stream.StreamSource

import net.sf.saxon.s9api.{SaxonApiException, XdmDestination}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ValidatePolicySuite extends AttributeMapperBase {

  val testDir = new File("src/test/resources/tests/validate-policy-tests")

  val xmlValidPolicies = new File(testDir, "valid").listFiles.toList.filter { f =>
    f.getName.endsWith("xml")
  }
  val xmlInvalidPolicies = new File(testDir, "invalid").listFiles.toList.filter { f =>
    f.getName.endsWith("xml")
  }

  val jsonValidPolicies = new File(testDir, "valid").listFiles.toList.filter { f =>
    f.getName.endsWith("json")
  }
  val jsonInvalidPolicies = new File(testDir, "invalid").listFiles.toList.filter { f =>
    f.getName.endsWith("json")
  }

  validators foreach { validator =>
    xmlValidPolicies foreach { policy =>
      test(s"A Valid Policy Validates ($policy validated with $validator)") {
        val source = new StreamSource(policy)
        AttributeMapper.validatePolicy(source, validator)
      }
    }

    xmlInvalidPolicies foreach { policy =>
      test(s"An Invalid Policy Fails Validation ($policy validated with $validator)") {
        val source = new StreamSource(policy)
        val e = intercept[SaxonApiException] {
          AttributeMapper.validatePolicy(source, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }

      test(s"An Invalid Policy Fails XSL Compilation ($policy compiled with $validator)") {
        val source = new StreamSource(policy)
        val e = intercept[SaxonApiException] {
          AttributeMapper.generateXSL(source, PolicyFormat.XML, new XdmDestination, validate = true, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }

      test(s"An Invalid Policy Fails XSLExec Compilation ($policy compiled with $validator)") {
        val source = new StreamSource(policy)
        val e = intercept[SaxonApiException] {
          AttributeMapper.generateXSLExec(source, PolicyFormat.XML, validate = true, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }
    }

    jsonValidPolicies foreach { policy =>
      test(s"A Valid Policy Validates ($policy validated with $validator)") {
        val source = new StreamSource(policy)
        val json = AttributeMapper.parseJsonNode(source)
        AttributeMapper.validatePolicy(json, validator)
      }
    }

    jsonInvalidPolicies foreach { policy =>
      test(s"An Invalid Policy Fails Validation ($policy validated with $validator)") {
        val source = new StreamSource(policy)
        val json = AttributeMapper.parseJsonNode(source)
        val e = intercept[SaxonApiException] {
          AttributeMapper.validatePolicy(json, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }

      test(s"An Invalid Policy Fails XSL Compilation ($policy compiled with $validator)") {
        val source = new StreamSource(policy)
        val json = AttributeMapper.parseJsonNode(source)
        val e = intercept[SaxonApiException] {
          AttributeMapper.generateXSL(json, new XdmDestination, validate = true, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }

      test(s"An Invalid Policy Fails XSLExec Compilation ($policy compiled with $validator)") {
        val source = new StreamSource(policy)
        val json = AttributeMapper.parseJsonNode(source)
        val e = intercept[SaxonApiException] {
          AttributeMapper.generateXSLExec(json, validate = true, validator)
        }
        assert(e.getCause.getMessage.contains("are not allowed in a policy path"))
      }
    }
  }
}

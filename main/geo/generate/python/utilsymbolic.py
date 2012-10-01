# Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
#
# This file is part of BoofCV (http://boofcv.org).
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Various utility functions for dealing with symbolic math and expanding the equations out
# for import into Java code
#
# Works with Sage Version 5.2

from numpy.core.fromnumeric import var
from numpy.linalg.linalg import det

from sage.all import *

def symMatrix( numRows , numCols , letter ):
  """Creates a matrix where each element is a unique symbol
  """
  n = max(len(str(numRows)),len(str(numCols)))
  format = '%s%0'+str(n)+'d'+'%0'+str(n)+'d'
  A = matrix(SR,numRows,numCols)
  for i in range(0,numRows):
    for j in range(0,numCols):
      A[i,j] = var(format % (letter,i,j) )

  return A

def symVector( numRows , letter ):
  """Creates a column vector where each element is a unique symbol
  """
  n = len(str(numRows))
  format = '%s%0'+str(n)+'d'
  A = matrix(SR,numRows,1)
  for i in range(0,numRows):
      A[i,0] = var(format % (letter,i) )

  return A

def symCrossMat3x3( v ):
    """ Creates a 3x3 skew symmetric cross product matrix from the provided 3-vector
    """

    A = matrix(SR,3,3)
    A[0,1] = -1*v[2][0]
    A[0,2] =  v[1][0]
    A[1,0] =  v[2][0]
    A[1,2] = -1*v[0][0]
    A[2,0] = -1*v[1][0]
    A[2,1] =  v[0][0]

    return A

def multScalarMatrix( s , A ):
    """ Multiplies the matrix symbolic matrix by the symbolic scalar
    """

    C = matrix(SR,A.nrows(),A.ncols())
    for i in range(0,A.nrows()):
        for j in range(0,A.ncols()):
            C[i,j] = (s*A[i,j])[0]
    return C

def expandPower( expression ):
  """Expands out variables which are multiplied by an integer value
  For example x^2 = x*x and x^4 = x*x*x*x
  """
  l = expression.split('*')

  # handle special case with a minus sign in front
  if l[0][0] == '-':
    l[0] = l[0][1:]
    expression = '-'
  else:
    expression = ''

  for s in l:
    if len(s) >= 3 and s[-2] == '^':
       var = s[:-2]
       expanded = var
       for i in range(int(s[-1])-1): expanded += '*'+var
       expression += expanded + '*'
    else:
       expression += s + '*'
  return expression[:-1]

def extractVarEq( expression , key ):
  """Expands the expression out and searches for all blocks of multiplication that are multiplied by the key.
  All other blocks are discarded and the key is removed from the selected blocks
  Example:  "x*a*b + y*a*a*b - c*x*d"  would output "a*b - c*d" if the key was 'x'
  """

  chars = set('xyz')
  # expand out and convert into a string
  expression = str(expression.expand())
  # Make sure negative symbols are not stripped and split into multiplicative blocks
  s = expression.replace('- ','-').split(' ')
  # Find blocks multiplied by the key and remove the key from the string
  if len(key) == 0:
    var = [w for w in s if len(w) != 1 and not any( c in chars for c in w )]
  else:
    var = [w[0:w.find(key)]+w[w.find(key)+len(key):] for w in s if w.find(key) != -1 ]

  # Handle the case where the key was not found
  if len(var) == 0:
      return ''

  # Fix problems left by stripping away the key
  var = [w.replace('-*','-') for w in var]
  for i in range(0,len(var)):
      if len(var[i]) == 0: var[i] = '1'
      elif len(var[i]) == 1 and var[i][0] == '-': var[i] = '-1'
      elif var[i][-1] == '*': var[i] = var[i][:-1]
      elif var[i][0] == '*': var[i] = var[i][1:]

  # Expand out power
  var = [expandPower(w) for w in var]

  # construct a string which can be compiled
  ret = var[0]
  for w in var[1:]:
    if w[0] == '-':
      ret += ' - '+w[1:]
    else:
      ret += ' + '+w

  return ret

def printData( var , eqs , keys ):
  f = open('%s.txt'%var,'w')
  for row,eq in enumerate(eqs):
    index = len(keys)*row
    for k in keys:
      f.write('%s.data[%d] = %s;\n'%(var,index,simplifyExpanded(extractVarEq(eq,k))))
      index += 1
  f.close()

# Reduces number of multiplications by moving the most common elements outside
def simplifyExpanded( input ):
  if not len(input): return ''

  def removeSubString( text , candidates ):
    for s in candidates:
      l = len(s)
      i = text.find(s)
      if i >= 0:
        if s[0] == '*' and s[-1] == '*':
          return text[0:i] +'*'+ text[i+l:]
        else: return text[0:i] + text[i+l:]
    return text

  def removeVariable( text , s ):
    return removeSubString(text,('*'+s+'*',s+'*','*'+s))

  def reconstruct( sequence ):
      output = sequence[0]
      for w in sequence[1:]:
        if w[0] == '-':
          output += ' - '+w[1:]
        else:
          output += ' + '+w
      return output

  s = input.replace('- ','-').split(' ')
  s = [w for w in s if len(w) > 1 ]

  # Find the frequency of each variable
  dict = {}
  for w in s:
    vars = w.replace('-','').split('*')
    for v in vars:
      if dict.has_key(v):
        dict[v] += 1
      else: dict[v] = 1

  bestVar = ''
  bestCount = 0
  for k,v in dict.items():
    if v > bestCount:
      bestCount = v
      bestVar = k

  include = []
  exclude = []

  for w in s:
      if bestVar in w:
          include.append(w)
      else:
          exclude.append(w)

  include = [removeVariable(w,bestVar) for w in include]

  output = bestVar + '*( '
  if bestVar[0].isdigit(): output += simplifyExpanded(reconstruct(include))
  else: output += reconstruct(include)
  output += ' )'

  if len(exclude):
      output += ' + '+simplifyExpanded( reconstruct(exclude))

  return output

// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import btools.util.BitCoderContext;
import btools.util.Crc32;


public final class BExpressionContext
{
  private  static final String CONTEXT_TAG = "---context:";
	
  private String context;
  private boolean _inOurContext = false;
  private BufferedReader _br = null;
  private boolean _readerDone = false;

  private BExpressionReceiver _receiver;

  private Map<String,Integer> lookupNumbers = new HashMap<String,Integer>();
  private ArrayList<BExpressionLookupValue[]> lookupValues = new ArrayList<BExpressionLookupValue[]>();
  private ArrayList<String> lookupNames = new ArrayList<String>();
  private ArrayList<int[]> lookupHistograms = new ArrayList<int[]>();

  private boolean lookupDataFrozen = false;

  private int[] lookupData = new int[0];

  private byte[] abBuf = new byte[256];
  
  private Map<String,Integer> variableNumbers = new HashMap<String,Integer>();

  private float[] variableData;


  // hash-cache for function results
  private byte[][] _arrayBitmap;
  private boolean[] _arrayInverse;
  private int[] _arrayCrc;

  private int currentHashBucket = -1;
  private byte[] currentByteArray = null;
  private boolean currentInverseDirection= false;

  public List<BExpression> expressionList;

  private int minWriteIdx;

  // build-in variable indexes for fast access
  private int costfactorIdx;
  private int turncostIdx;
  private int uphillcostfactorIdx;
  private int downhillcostfactorIdx;
  private int initialcostIdx;
  private int nodeaccessgrantedIdx;

  private float[] _arrayCostfactor;
  private float[] _arrayTurncost;
  private float[] _arrayUphillCostfactor;
  private float[] _arrayDownhillCostfactor;
  private float[] _arrayInitialcost;
  private float[] _arrayNodeAccessGranted;

  public float getCostfactor() { return _arrayCostfactor[currentHashBucket]; }
  public float getTurncost() { return _arrayTurncost[currentHashBucket]; }
  public float getUphillCostfactor() { return _arrayUphillCostfactor[currentHashBucket]; }
  public float getDownhillCostfactor() { return _arrayDownhillCostfactor[currentHashBucket]; }
  public float getInitialcost() { return _arrayInitialcost[currentHashBucket]; }
  public float getNodeAccessGranted() { return _arrayNodeAccessGranted[currentHashBucket]; }

  private int linenr;

  public BExpressionMetaData meta;
  private boolean lookupDataValid = false;

  public BExpressionContext( String context, BExpressionMetaData meta )
  {
    this( context, 4096, meta );
  }

  /**
   * Create an Expression-Context for the given node
   *
   * @param context  global, way or node - context of that instance
   * @param hashSize  size of hashmap for result caching
   */
  public BExpressionContext( String context, int hashSize, BExpressionMetaData meta )
  {
     this.context = context;
     this.meta = meta;
     
     if ( meta != null ) meta.registerListener(context, this );

     if ( Boolean.getBoolean( "disableExpressionCache" ) ) hashSize = 1;
      
     _arrayBitmap = new byte[hashSize][];
     _arrayInverse = new boolean[hashSize];
     _arrayCrc = new int[hashSize];

    _arrayCostfactor = new float[hashSize];
    _arrayTurncost = new float[hashSize];
    _arrayUphillCostfactor = new float[hashSize];
    _arrayDownhillCostfactor = new float[hashSize];
    _arrayInitialcost = new float[hashSize];
    _arrayNodeAccessGranted = new float[hashSize];
  }

  /**
   * encode internal lookup data to a byte array
   */
  public byte[] encode()
  {
	if ( !lookupDataValid ) throw new IllegalArgumentException( "internal error: encoding undefined data?" );
    return encode( lookupData );
  }

  public byte[] encode( int[] ld )
  {
	if ( !meta.readVarLength ) return encodeFix( ld ); 
	  
	// start with first bit hardwired ("reversedirection")
	BitCoderContext ctx = new BitCoderContext( abBuf );
	ctx.encodeBit( ld[0] != 0 );
	
	int skippedTags = 0;
	int nonNullTags= 0;
  	
    // all others are generic
    for( int inum = 1; inum < lookupValues.size(); inum++ ) // loop over lookup names
    {
      int d = ld[inum];
      if ( d == 0 )
      {
        skippedTags++;
        continue;
      }
      ctx.encodeVarBits( skippedTags+1 );
      nonNullTags++;
      skippedTags = 0;
      
      // 0 excluded already, 1 (=unknown) we rotate up to 8
      // to have the good code space for the popular values
      int dd = d < 2 ? 7 : ( d < 9 ? d - 2 : d - 1);
      ctx.encodeVarBits(  dd );
    }
    ctx.encodeVarBits( 0 );
    
    if ( nonNullTags == 0) return null;
    
    int len = ctx.getEncodedLength();
    byte[] ab = new byte[len];
    System.arraycopy( abBuf, 0, ab, 0, len );
    
    
    // crosscheck: decode and compare
    int[] ld2 = new int[lookupValues.size()];
    decode( ld2, false, ab );
    for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
    {
      if ( ld2[inum] != ld[inum] ) throw new RuntimeException( "assertion failed encoding " + getKeyValueDescription(false, ab) );
    }    
    
    return ab;
  }

  /**
   * encode lookup data to a 64-bit word
   */
  public byte[] encodeFix( int[] ld )
  {
    long w = 0;
    for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
    {
      int n = lookupValues.get(inum).length - 1;
      int d = ld[inum];
      if ( n == 2 ) { n = 1; d = d == 2 ? 1 : 0; } // 1-bit encoding for booleans

      while( n != 0 ) { n >>= 1; w <<= 1; }
      w |= (long)d;
    }
    if ( w == 0) return null;
    
    byte[] ab = new byte[8];
    int aboffset = 0;
	ab[aboffset++] = (byte)( (w >> 56) & 0xff );
    ab[aboffset++] = (byte)( (w >> 48) & 0xff );
	ab[aboffset++] = (byte)( (w >> 40) & 0xff );
    ab[aboffset++] = (byte)( (w >> 32) & 0xff );
	ab[aboffset++] = (byte)( (w >> 24) & 0xff );
    ab[aboffset++] = (byte)( (w >> 16) & 0xff );
	ab[aboffset++] = (byte)( (w >>  8) & 0xff );
    ab[aboffset++] = (byte)( (w      ) & 0xff );
    return ab;
  }
  

  /**
   * decode byte array to internal lookup data
   */
  public void decode( byte[] ab )
  {
    decode( lookupData, false, ab );
    lookupDataValid = true;
  }

  /**
   * decode a byte-array into a lookup data array
   */
  private void decode( int[] ld, boolean inverseDirection, byte[] ab )
  {
	if ( !meta.readVarLength ) { decodeFix( ld, ab ); return; }

    BitCoderContext ctx = new BitCoderContext(ab);
	  
    // start with first bit hardwired ("reversedirection")
  	ld[0] = inverseDirection ^ ctx.decodeBit() ? 2 : 0;
  	
    // all others are generic
  	int inum = 1;
    for(;;)
    {
      int delta = ctx.decodeVarBits();
      if ( delta == 0) break;
      if ( inum + delta > ld.length ) break; // higher minor version is o.k.
      
      while ( delta-- > 1 ) ld[inum++] = 0;

      // see encoder for value rotation
      int dd = ctx.decodeVarBits();
      int d = dd == 7 ? 1 : ( dd < 7 ? dd + 2 : dd + 1);
      if ( d >= lookupValues.get(inum).length ) d = 1; // map out-of-range to unknown
      ld[inum++] = d;
    }
    while( inum < ld.length ) ld[inum++] = 0;
  }

  /**
   * decode old, 64-bit-fixed-length format
   */
  public void decodeFix( int[] ld, byte[] ab )
  {
	  int idx = 0;
      long i7 = ab[idx++]& 0xff;
      long i6 = ab[idx++]& 0xff;
      long i5 = ab[idx++]& 0xff;
      long i4 = ab[idx++]& 0xff;
      long i3 = ab[idx++]& 0xff;
      long i2 = ab[idx++]& 0xff;
      long i1 = ab[idx++]& 0xff;
      long i0 = ab[idx++]& 0xff;
      long w =  (i7 << 56) + (i6 << 48) + (i5 << 40) + (i4 << 32) + (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;

    for( int inum = lookupValues.size()-1; inum >= 0; inum-- ) // loop over lookup names
    {
      int nv = lookupValues.get(inum).length;
      int n = nv == 3 ? 1 : nv-1; // 1-bit encoding for booleans
      int m = 0;
      long ww = w;
      while( n != 0 ) { n >>= 1; ww >>= 1; m = m<<1 | 1; }
      int d = (int)(w & m);
      if ( nv == 3 && d == 1 ) d = 2; // 1-bit encoding for booleans
      ld[inum] = d;
      w = ww;
    }
  }

  public String getKeyValueDescription( boolean inverseDirection, byte[] ab )
  {
    int inverseBitByteIndex =  meta.readVarLength ? 0 : 7;
//    int abLen = ab.length;

	StringBuilder sb = new StringBuilder( 200 );
    decode( lookupData, inverseDirection, ab );
    for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
    {
      BExpressionLookupValue[] va = lookupValues.get(inum);
      String value = va[lookupData[inum]].toString();
      if ( value != null && value.length() > 0 )
      {
        sb.append( " " + lookupNames.get( inum ) + "=" + value );
      }
    }
    return sb.toString();
  }

  private int parsedLines = 0;
  private boolean fixTagsWritten = false;
  
  public void parseMetaLine( String line )
  {
      parsedLines++;
      StringTokenizer tk = new StringTokenizer( line, " " );
      String name = tk.nextToken();
      String value = tk.nextToken();
      int idx = name.indexOf( ';' );
      if ( idx >= 0 ) name = name.substring( 0, idx );

      if ( meta.readVarLength )
      {
        if ( !fixTagsWritten )
        {
          fixTagsWritten = true;
          if ( "way".equals( context ) ) addLookupValue( "reversedirection", "yes", null );
          else if ( "node".equals( context ) ) addLookupValue( "nodeaccessgranted", "yes", null );
        }
        if ( "reversedirection".equals( name ) ) return; // this is hardcoded
        if ( "nodeaccessgranted".equals( name ) ) return; // this is hardcoded
      }
      BExpressionLookupValue newValue = addLookupValue( name, value, null );

      // add aliases
      while( newValue != null && tk.hasMoreTokens() ) newValue.addAlias( tk.nextToken() );
  }
  
  public void finishMetaParsing()
  {
    if ( parsedLines == 0 && !"global".equals(context) )
    {
      throw new IllegalArgumentException( "lookup table does not contain data for context " + context + " (old version?)" );
    }

    // post-process metadata:
    lookupDataFrozen = true;
  }

  public void evaluate( int[] lookupData2 )
  {
    lookupData = lookupData2;
    for( BExpression exp: expressionList)
    {
      exp.evaluate( this );
    }
  }

  public long requests;
  public long requests2;
  public long cachemisses;

  /**
   * evaluates the data in the given byte array
   * 
   * @return true if the data is equivilant to the last calls data
   */
  public boolean evaluate( boolean inverseDirection, byte[] ab, BExpressionReceiver receiver )
  {
	 requests ++;
	 lookupDataValid = false; // this is an assertion for a nasty pifall

	 int inverseBitByteIndex = meta.readVarLength ? 0 : 7;

	 // calc hash bucket from crc
	 int lastHashBucket = currentHashBucket;
     int crc  = Crc32.crcWithInverseBit(ab, inverseDirection ? inverseBitByteIndex : -1 );
     int hashSize = _arrayBitmap.length;
     currentHashBucket =  (crc & 0xfffffff) % hashSize;
     currentByteArray = ab;
     currentInverseDirection = inverseDirection;
     byte[] abBucket = _arrayBitmap[currentHashBucket];
     boolean inverseBucket = _arrayInverse[currentHashBucket];
	 if ( ab == abBucket && inverseBucket == inverseDirection ) // fast identity check
     {
	   return lastHashBucket == currentHashBucket;
	 }
	 requests2++;

	 // compare input value to hash bucket content
     boolean hashBucketEquals = false;
     if ( crc == _arrayCrc[currentHashBucket] )
     {
       int abLen = ab.length;
       if ( abBucket != null && abBucket.length == ab.length )
       {
    	 hashBucketEquals = true;
    	 boolean isInverse = inverseDirection ^ inverseBucket;
         for( int i=0; i<abLen; i++ )
         {
           byte b = ab[i];
           if ( isInverse && i == inverseBitByteIndex ) b ^= 1;
    	   if ( abBucket[i] != b ) { hashBucketEquals = false; break; }
         }
       }
     }
     if ( hashBucketEquals ) return lastHashBucket == currentHashBucket;
     cachemisses++;
     
     _arrayBitmap[currentHashBucket] = currentByteArray;
     _arrayInverse[currentHashBucket] = currentInverseDirection;
     _arrayCrc[currentHashBucket] = crc;

     _receiver = receiver;

     decode( lookupData, currentInverseDirection, currentByteArray );
     evaluate( lookupData );

     _arrayCostfactor[currentHashBucket] = variableData[costfactorIdx];
     _arrayTurncost[currentHashBucket] = variableData[turncostIdx];
     _arrayUphillCostfactor[currentHashBucket] = variableData[uphillcostfactorIdx];
     _arrayDownhillCostfactor[currentHashBucket] = variableData[downhillcostfactorIdx];
     _arrayInitialcost[currentHashBucket] = variableData[initialcostIdx];
     _arrayNodeAccessGranted[currentHashBucket] = variableData[nodeaccessgrantedIdx];

     _receiver = null;
     return false;
  }

  public void dumpStatistics()
  {
    TreeMap<String,String> counts = new TreeMap<String,String>();
    // first count
    for( String name: lookupNumbers.keySet() )
    {
      int cnt = 0;
      int inum = lookupNumbers.get(name).intValue();
      int[] histo = lookupHistograms.get(inum);
//    if ( histo.length == 500 ) continue;
      for( int i=2; i<histo.length; i++ )
      {
        cnt += histo[i];
      }
      counts.put( "" + ( 1000000000 + cnt) + "_" + name, name );
    }

    while( counts.size() > 0 )
    {
      String key = counts.lastEntry().getKey();
      String name = counts.get(key);
      counts.remove( key );
      int inum = lookupNumbers.get(name).intValue();
      BExpressionLookupValue[] values = lookupValues.get(inum);
      int[] histo = lookupHistograms.get(inum);
      if ( values.length == 1000 ) continue;
      String[] svalues = new String[values.length];
      for( int i=0; i<values.length; i++ )
      {
        String scnt = "0000000000" + histo[i];
        scnt = scnt.substring( scnt.length() - 10 );
        svalues[i] =  scnt + " " + values[i].toString();
      }
      Arrays.sort( svalues );
      for( int i=svalues.length-1; i>=0; i-- )
      {
        System.out.println( name + ";" + svalues[i] );
      }
    }
  }

  /**
   * @return a new lookupData array, or null if no metadata defined
   */
  public int[] createNewLookupData()
  {
    if ( lookupDataFrozen )
    {
      return new int[lookupValues.size()];
    }
    return null;
  }

  /**
   * add a new lookup-value for the given name to the given lookupData array.
   * If no array is given (null value passed), the value is added to
   * the context-binded array. In that case, unknown names and values are
   * created dynamically.
   *
   * @return a newly created value element, if any, to optionally add aliases
   */
  public BExpressionLookupValue addLookupValue( String name, String value, int[] lookupData2 )
  {
    BExpressionLookupValue newValue = null;
    Integer num = lookupNumbers.get( name );
    if ( num == null )
    {
      if ( lookupData2 != null )
      {
        // do not create unknown name for external data array
        return newValue;
      }

      // unknown name, create
      num = new Integer( lookupValues.size() );
      lookupNumbers.put( name, num );
      lookupNames.add( name );
      lookupValues.add( new BExpressionLookupValue[]{ new BExpressionLookupValue( "" )
                                                    , new BExpressionLookupValue( "unknown" ) } );
      lookupHistograms.add( new int[2] );
      int[] ndata = new int[lookupData.length+1];
      System.arraycopy( lookupData, 0, ndata, 0, lookupData.length );
      lookupData = ndata;
    }

    // look for that value
    int inum = num.intValue();
    BExpressionLookupValue[] values = lookupValues.get( inum );
    int[] histo = lookupHistograms.get( inum );
    int i=0;
    for( ; i<values.length; i++ )
    {
      BExpressionLookupValue v = values[i];
      if ( v.matches( value ) ) break;
    }
    if ( i == values.length )
    {
      if ( lookupData2 != null )
      {
        // do not create unknown value for external data array,
        // record as 'other' instead
        lookupData2[inum] = 1;
        return newValue;
      }

      if ( i == 499 )
      {
        // System.out.println( "value limit reached for: " + name );
      }
      if ( i == 500 )
      {
        return newValue;
      }
      // unknown value, create
      BExpressionLookupValue[] nvalues = new BExpressionLookupValue[values.length+1];
      int[] nhisto = new int[values.length+1];
      System.arraycopy( values, 0, nvalues, 0, values.length );
      System.arraycopy( histo, 0, nhisto, 0, histo.length );
      values = nvalues;
      histo = nhisto;
      newValue = new BExpressionLookupValue( value );
      values[i] = newValue;
      lookupHistograms.set(inum, histo);
      lookupValues.set(inum, values);
    }

    histo[i]++;

    // finally remember the actual data
    if ( lookupData2 != null ) lookupData2[inum] = i;
    else lookupData[inum] = i;
    return newValue;
  }

  /**
   * add a value-index to to internal array
   * value-index means 0=unknown, 1=other, 2=value-x, ...
   */
  public void addLookupValue( String name, int valueIndex )
  {
    Integer num = lookupNumbers.get( name );
    if ( num == null )
    {
      return;
    }

    // look for that value
    int inum = num.intValue();
    int nvalues = lookupValues.get( inum ).length;
    if ( valueIndex < 0 || valueIndex >= nvalues ) throw new IllegalArgumentException( "value index out of range for name " + name + ": " + valueIndex );
    lookupData[inum] = valueIndex;
  }

  /**
   * special hack for yes/proposed relations:
   * add a lookup value if not yet a smaller, >1 value was added
   * add a 2=yes if the provided value is out of range
   * value-index means here 0=unknown, 1=other, 2=yes, 3=proposed
   */
  public void addSmallestLookupValue( String name, int valueIndex )
  {
    Integer num = lookupNumbers.get( name );
    if ( num == null )
    {
      return;
    }

    // look for that value
    int inum = num.intValue();
    int nvalues = lookupValues.get( inum ).length;
    int oldValueIndex = lookupData[inum];
    if ( oldValueIndex > 1 && oldValueIndex < valueIndex )
    {
      return;
    }
    if ( valueIndex >= nvalues )
    {
      valueIndex = nvalues-1;
    }
    if ( valueIndex < 0 ) throw new IllegalArgumentException( "value index out of range for name " + name + ": " + valueIndex );
    lookupData[inum] = valueIndex;
  }

  public boolean getBooleanLookupValue( String name )
  {
    Integer num = lookupNumbers.get( name );
    return num != null && lookupData[num.intValue()] == 2;
  }

  public void parseFile( File file, String readOnlyContext )
  {
    try
    {
      if ( readOnlyContext != null )
      {
        linenr = 1;
        String realContext = context;
        context = readOnlyContext;
        expressionList = _parseFile( file );
        variableData = new float[variableNumbers.size()];
        evaluate( lookupData ); // lookupData is dummy here - evaluate just to create the variables
        context = realContext;
      }
      linenr = 1;
      minWriteIdx = variableData == null ? 0 : variableData.length;

      costfactorIdx = getVariableIdx( "costfactor", true );
      turncostIdx = getVariableIdx( "turncost", true );
      uphillcostfactorIdx = getVariableIdx( "uphillcostfactor", true );
      downhillcostfactorIdx = getVariableIdx( "downhillcostfactor", true );
      initialcostIdx = getVariableIdx( "initialcost", true );
      nodeaccessgrantedIdx = getVariableIdx( "nodeaccessgranted", true );


      expressionList = _parseFile( file );
      float[] readOnlyData = variableData;
      variableData = new float[variableNumbers.size()];
      for( int i=0; i<minWriteIdx; i++ )
      {
        variableData[i] = readOnlyData[i];
      }
    }
    catch( Exception e )
    {
      if ( e instanceof IllegalArgumentException )
      {
        throw new IllegalArgumentException( "ParseException at line " + linenr + ": " + e.getMessage() );
      }
      throw new RuntimeException( e );
    }
    if ( expressionList.size() == 0 )
    {
        throw new IllegalArgumentException( file.getAbsolutePath()
             + " does not contain expressions for context " + context + " (old version?)" );
    }
  }

  private List<BExpression> _parseFile( File file ) throws Exception
  {
    _br = new BufferedReader( new FileReader( file ) );
    _readerDone = false;
    List<BExpression> result = new ArrayList<BExpression>();
    for(;;)
    {
      BExpression exp = BExpression.parse( this, 0 );
      if ( exp == null ) break;
      result.add( exp );
    }
    _br.close();
    _br = null;
    return result;
  }


  public float getVariableValue( String name, float defaultValue )
  {
    Integer num = variableNumbers.get( name );
    return num == null ? defaultValue : getVariableValue( num.intValue() );
  }

  float getVariableValue( int variableIdx )
  {
    return variableData[variableIdx];
  }

  int getVariableIdx( String name, boolean create )
  {
    Integer num = variableNumbers.get( name );
    if ( num == null )
    {
      if ( create )
      {
        num = new Integer( variableNumbers.size() );
        variableNumbers.put( name, num );
      }
      else
      {
        return -1;
      }
    }
    return num.intValue();
  }

  int getMinWriteIdx()
  {
    return minWriteIdx;
  }

  float getLookupMatch( int nameIdx, int valueIdx )
  {
    return lookupData[nameIdx] == valueIdx ? 1.0f : 0.0f;
  }

  public int getLookupNameIdx( String name )
  {
    Integer num = lookupNumbers.get( name );
    return num == null ? -1 : num.intValue();
  }

  int getLookupValueIdx( int nameIdx, String value )
  {
    BExpressionLookupValue[] values = lookupValues.get( nameIdx );
    for( int i=0; i< values.length; i++ )
    {
      if ( values[i].equals( value ) ) return i;
    }
    return -1;
  }


  String parseToken() throws Exception
  {
    for(;;)
    {
      String token = _parseToken();
      if ( token == null ) return null;
      if ( token.startsWith( CONTEXT_TAG ) )
      {
        _inOurContext = token.substring( CONTEXT_TAG.length() ).equals( context );
      }
      else if ( _inOurContext )
      {
        return token;
      }
    }
  }


  private String _parseToken() throws Exception
  {
    StringBuilder sb = new StringBuilder(32);
    boolean inComment = false;
    for(;;)
    {
      int ic = _readerDone ? -1 : _br.read();
      if ( ic < 0 )
      {
        if ( sb.length() == 0 ) return null;
        _readerDone = true;
         return sb.toString();
      }
      char c = (char)ic;
      if ( c == '\n' ) linenr++;

      if ( inComment )
      {
        if ( c == '\r' || c == '\n' ) inComment = false;
        continue;
      }
      if ( Character.isWhitespace( c ) )
      {
        if ( sb.length() > 0 ) return sb.toString();
        else continue;
      }
      if ( c == '#' && sb.length() == 0 ) inComment = true;
      else sb.append( c );
    }
  }

  float assign( int variableIdx, float value )
  {
    variableData[variableIdx] = value;
    return value;
  }

  void expressionWarning( String message )
  {
    _arrayBitmap[currentHashBucket] = null; // no caching if warnings
     if ( _receiver != null ) _receiver.expressionWarning( context, message );
  }
}

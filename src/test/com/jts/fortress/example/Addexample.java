/*
 * Copyright (c) 2009-2011. Joshua Tree Software, LLC.  All Rights Reserved.
 */

package com.jts.fortress.example;

import java.util.ArrayList;
import java.util.List;

public class Addexample

{
	final private List<Example> examples = new ArrayList<Example>();


	public Addexample() { }


	public void addExample(Example example)
	{
		this.examples.add(example);
	}


	public List<Example> getExamples()
	{
		return this.examples;
	}
}

